package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FpsCounter;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * LaunchStageView — the pre-render stage + bundled loading video.
 *
 * v4 — the two remaining root causes why the video NEVER actually played:
 *
 *  ① PREPARE-RACE (fatal): MainActivity's surface-ready callback fires within
 *    ~100–500 ms of the layout attach — LONG BEFORE the JVM exists. v2/v3 set
 *    sGameRendering there, and the MediaPlayer's onPrepared gate then read
 *    "game already rendering" and silently RELEASED the freshly prepared
 *    player without ever starting it. Every launch. The fix: surface-ready
 *    only ARMS the first-frame release watch; it never gates prepare/start.
 *
 *  ② SURFACE GC RACE: the Surface wrapper handed to MediaPlayer was created
 *    inline (new Surface(mSurface)) with no strong reference — the wrapper
 *    could be garbage-collected → native surface released mid-play → blank.
 *    The Surface is now held for the player's whole lifetime.
 *
 * Visibility (v3, kept): the video lives in @id/video_stage_host — ABOVE the
 * game surface, below the log console, touch-transparent.
 *
 * Release contract kept exactly as user asked: the video plays across the
 * whole boot and is released on the FIRST PRESENTED FRAME (native presents
 * counter, 250 ms latch; 18 s hard cap for bridges without a present hook).
 */
public class LaunchStageView extends FrameLayout
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "LaunchStageView";
    public static final String PREF_KEY_STYLE = "loadingScreenStyle";
    public static final String STYLE_BLACK = "black";
    public static final String STYLE_VIDEO = "video";
    /** Bundled loading video (uncompressed entry — mp4 is noCompress in AGP). */
    private static final String ASSET_VIDEO = "csl_loading.mp4";
    private static final long PREP_WATCHDOG_MS = 7000L;
    /** Safety cap for bridges without a present hook (zink/vulkan). */
    private static final long FIRST_FRAME_CAP_MS = 18000L;
    private static final long FIRST_FRAME_POLL_MS = 250L;

    // ── static launch-session registry ──
    @Nullable private static WeakReference<LaunchStageView> sActive;

    private View mStaticStage;
    @Nullable private TextureView mVideoView;
    @Nullable private ViewGroup mVideoHost;
    @Nullable private MediaPlayer mPlayer;
    @Nullable private Surface mPlayerSurface;   // strong ref — root-cause ②
    @Nullable private SurfaceTexture mSurface;
    private boolean mStopped;
    private boolean mPrepareStarted;
    private long mPresentBaseline = -1;  // armed at surface-ready
    private boolean mFirstFrameWatch;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPrepWatchdog = () -> {
        Log.w(TAG, "video: prepare watchdog — classic stage");
        fallbackToStatic();
    };
    private final Runnable mFirstFrameCap = () -> {
        Log.i(TAG, "video: first-frame cap reached — releasing player");
        stopVideoNow();
    };
    private final Runnable mFirstFramePoll = new Runnable() {
        @Override public void run() {
            if (mStopped) return;
            long total = FpsCounter.getTotalPresents();
            if (mPresentBaseline < 0) mPresentBaseline = total; // native late — keep baselining
            if (total >= 0 && mPresentBaseline >= 0 && total > mPresentBaseline) {
                Log.i(TAG, "video: FIRST FRAME presented — releasing player now");
                stopVideoNow();
                return;
            }
            mHandler.postDelayed(this, FIRST_FRAME_POLL_MS);
        }
    };

    public LaunchStageView(@NonNull Context context) { super(context); }
    public LaunchStageView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public LaunchStageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    // ───────────────────────── public statics ─────────────────────────

    /**
     * Game surface created (called from MainActivity). NOTE: this fires LONG
     * before the game draws anything (pre-JVM). It only ARMS the first-frame
     * watch — the video keeps playing through JVM start + MC init and is
     * released exactly when a real frame presents.
     */
    public static void onGameRenderStarted() {
        LaunchStageView v = sActive != null ? sActive.get() : null;
        if (v != null) v.armFirstFrameWatch();
    }

    // ───────────────────────── lifecycle ─────────────────────────

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) mStaticStage = getChildAt(0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sActive = new WeakReference<>(this);
        bind();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopVideoNow();
        if (sActive != null && sActive.get() == this) sActive = null;
        super.onDetachedFromWindow();
    }

    // ───────────────────────── bind decision ─────────────────────────

    private void bind() {
        if (mStaticStage == null && getChildCount() > 0) mStaticStage = getChildAt(0);

        // Step-logged gates: every decision lands in logcat (tag above), so a
        // silently-black launch is diagnosable gate-by-gate.
        String mode;
        try {
            android.content.SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
            String src = "DEFAULT_PREF";
            if (prefs == null) { // robust: read the settings file directly
                prefs = getContext().getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
                src = "direct-file";
            }
            mode = prefs.getString(PREF_KEY_STYLE, STYLE_BLACK);
            Log.i(TAG, "bind: loadingScreenStyle=" + mode + " (prefs=" + src + ")");
        } catch (Throwable t) { Log.w(TAG, "bind: style read failed", t); return; }
        if (mode == null || !mode.toLowerCase().contains(STYLE_VIDEO)) {
            Log.i(TAG, "bind: classic stage (user picked black)");
            return;
        }

        // Bundled asset must exist & be openable as a raw fd (mp4 ships
        // uncompressed in the APK). If anything is off → static stage.
        AssetFileDescriptor probe = null;
        try {
            probe = getContext().getAssets().openFd(ASSET_VIDEO);
            Log.i(TAG, "bind: bundled video ok (" + probe.getLength() + " bytes)");
        } catch (IOException e) {
            Log.w(TAG, "bind: bundled video missing — classic stage", e);
            return;
        } finally {
            if (probe != null) try { probe.close(); } catch (IOException ignored) {}
        }

        // v3 fix (kept): attach above the game surface via the dedicated host.
        ViewGroup host = null;
        try {
            View root = getRootView();
            if (root != null) host = root.findViewById(R.id.video_stage_host);
        } catch (Throwable ignored) {}
        mVideoHost = host != null ? host : this; // defensive fallback

        // TextureView (not SurfaceView): obeys normal view compositing.
        mVideoView = new TextureView(getContext());
        // VISIBLE + alpha 0 — a GONE TextureView NEVER receives a SurfaceTexture,
        // so playback could never even start. The reveal fades in on the first
        // decoded frame (no black flash, no early pop).
        mVideoView.setVisibility(View.VISIBLE);
        mVideoView.setAlpha(0f);
        mVideoView.setSurfaceTextureListener(this);
        mVideoHost.addView(mVideoView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mHandler.postDelayed(mPrepWatchdog, PREP_WATCHDOG_MS);
    }

    // ───────────────────────── playback ─────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        mSurface = st;
        // v4: only mStopped gates anymore — surface readiness of the GAME is a
        // release signal, never a "don't start" signal (root-cause ①).
        if (mStopped || mPrepareStarted) return;
        mPrepareStarted = true;
        startPlayer();
    }

    /** Build + arm the MediaPlayer against the bundled asset. */
    private void startPlayer() {
        if (mStopped || mSurface == null) return;
        AssetFileDescriptor afd = null;
        try {
            afd = getContext().getAssets().openFd(ASSET_VIDEO);
            MediaPlayer mp = new MediaPlayer();
            mPlayer = mp;
            Log.i(TAG, "video: preparing bundled asset (" + afd.getLength() + " bytes)");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            // mp owns the media position now; closing the afd is safe per docs.
            try { afd.close(); } catch (IOException ignored) {}
            afd = null;
            // Hold the Surface STRONGLY for the player's lifetime (root-cause ②).
            mPlayerSurface = new Surface(mSurface);
            mp.setSurface(mPlayerSurface);
            mp.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setVolume(0f, 0f);              // loading screen stays silent
            mp.setLooping(true);               // loop until the first REAL frame
            mp.setOnPreparedListener(p -> {
                if (mStopped || mPlayer != p) { releaseQuietly(p); return; }
                try {
                    p.start();
                    // Surface became ready while we were preparing (common):
                    // the first-frame watch may already be armed elsewhere.
                } catch (Throwable t) { onPlaybackFailure(); }
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mVideoView != null) {
                    Log.i(TAG, "video: first decoded frame on screen — revealing");
                    mHandler.removeCallbacks(mPrepWatchdog);
                    mVideoView.animate().alpha(1f).setDuration(260).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "video: onError what=" + what + " extra=" + extra + " (bundled asset)");
                onPlaybackFailure();
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "video prepare failed", t);
            if (afd != null) try { afd.close(); } catch (IOException ignored) {}
            onPlaybackFailure();
        }
    }

    /**
     * Unified fail-safe: a bundled asset should never fail, but if anything
     * does, we drop straight to the existing static stage. Never throws; the
     * launcher can never crash from the loading video.
     */
    private void onPlaybackFailure() {
        Log.w(TAG, "video: giving up — classic stage");
        fallbackToStatic();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        if (mSurface == st) mSurface = null;
        stopVideoNow();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}

    // ───────────────────────── first-frame release ─────────────────────────

    /** Armed at surface-ready; releases the player on the FIRST presented frame. */
    private synchronized void armFirstFrameWatch() {
        if (mStopped || mVideoView == null || mFirstFrameWatch) return;
        mFirstFrameWatch = true;
        mPresentBaseline = FpsCounter.getTotalPresents();
        Log.i(TAG, "video: first-frame watch armed (baseline=" + mPresentBaseline + ", cap=" + FIRST_FRAME_CAP_MS + "ms)");
        mHandler.postDelayed(mFirstFramePoll, FIRST_FRAME_POLL_MS);
        mHandler.postDelayed(mFirstFrameCap, FIRST_FRAME_CAP_MS); // zink/vulkan safety
    }

    // ───────────────────────── stop / fallback ─────────────────────────

    /** Absolute fallback: tear down all video state, keep the static stage. */
    private synchronized void fallbackToStatic() {
        innerStop(true);
    }

    /** Immediate stop — first frame presented / detach / surface destroyed. */
    private synchronized void stopVideoNow() {
        if (mPlayer != null || mVideoView != null)
            Log.i(TAG, "video: stop — releasing player");
        innerStop(true);
    }

    private void innerStop(boolean removeView) {
        mStopped = true;
        mHandler.removeCallbacks(mPrepWatchdog);
        mHandler.removeCallbacks(mFirstFramePoll);
        mHandler.removeCallbacks(mFirstFrameCap);
        MediaPlayer p = mPlayer;
        mPlayer = null;
        releaseQuietly(p);
        Surface s = mPlayerSurface;    // release only after the player is dead
        mPlayerSurface = null;
        if (s != null) try { s.release(); } catch (Throwable ignored) {}
        if (removeView && mVideoView != null) {
            TextureView v = mVideoView;
            mVideoView = null;
            try {
                v.setSurfaceTextureListener(null);
                v.animate().cancel();
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent != null) parent.removeView(v);
            } catch (Throwable ignored) {}
        }
        mVideoHost = null;
    }

    private static void releaseQuietly(@Nullable MediaPlayer p) {
        if (p == null) return;
        try { p.setOnPreparedListener(null); p.setOnInfoListener(null); p.setOnErrorListener(null); } catch (Throwable ignored) {}
        try { p.stop(); } catch (Throwable ignored) {}
        try { p.reset(); } catch (Throwable ignored) {}
        try { p.release(); } catch (Throwable ignored) {}
    }
}
