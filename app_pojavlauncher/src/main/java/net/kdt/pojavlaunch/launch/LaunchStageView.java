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
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * LaunchStageView — the pre-render stage behind the game surface.
 *
 * Holds the classic (existing) static loading stage as its XML child and can
 * upgrade it to a BUNDLED loading video when the user picked
 * "Video Loading Screen" (loadingScreenStyle == video).
 *
 * Item-5 redesign: the video now ships INSIDE the APK as
 * assets/csl_loading.mp4 (~2.7 MB, 720p, H.264, no audio track, faststart).
 * No network, no remote config, no cache layer — playback starts instantly and
 * identically on every device and every launch. The remote streaming system
 * (RemoteConfigManager / LoadingVideoCache) is left in place but no longer
 * consulted here.
 *
 * Playback contract (user req):
 *  - Loops continuously until the game renders its first frame.
 *  - {@link #onGameRenderStarted()} fires on the surface-ready signal and the
 *    player is released on that exact frame — no delay, no leak.
 *  - TextureView stays alpha-0 until the first decoded frame is on screen
 *    (MEDIA_INFO_VIDEO_RENDERING_START), so there is never a black flash.
 *  - Every failure path silently keeps the existing static stage.
 */
public class LaunchStageView extends FrameLayout
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "LaunchStageView";
    public static final String PREF_KEY_STYLE = "loadingScreenStyle";
    public static final String STYLE_BLACK = "black";
    public static final String STYLE_VIDEO = "video";
    /** Bundled loading video (uncompressed entry — mp4 is noCompress in AGP). */
    private static final String ASSET_VIDEO = "csl_loading.mp4";
    private static final long WATCHDOG_MS = 7000L;

    // ── static launch-session registry ──
    private static volatile boolean sGameRendering;
    @Nullable private static WeakReference<LaunchStageView> sActive;

    private View mStaticStage;
    @Nullable private TextureView mVideoView;
    @Nullable private MediaPlayer mPlayer;
    @Nullable private SurfaceTexture mSurface;
    private boolean mStopped;
    private boolean mPrepareStarted;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdog = () -> {
        Log.w(TAG, "video: watchdog timeout (" + WATCHDOG_MS + "ms) — classic stage");
        fallbackToStatic();
    };

    public LaunchStageView(@NonNull Context context) { super(context); }
    public LaunchStageView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public LaunchStageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    // ───────────────────────── public statics ─────────────────────────

    /** Game render started (surface-ready) — kill the video immediately. */
    public static void onGameRenderStarted() {
        sGameRendering = true;
        LaunchStageView v = sActive != null ? sActive.get() : null;
        if (v != null) v.stopVideoNow();
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
        sGameRendering = false;                 // fresh launch session
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
        if (sGameRendering) return;

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
        if (!STYLE_VIDEO.equals(mode)) {
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

        // TextureView (not SurfaceView): obeys normal view compositing, so the
        // static stage stays visible underneath until the first video frame.
        mVideoView = new TextureView(getContext());
        // VISIBLE + alpha 0 — a GONE TextureView NEVER receives a SurfaceTexture,
        // so playback could never even start. The reveal fades in on the first
        // decoded frame (no black flash, no early pop).
        mVideoView.setVisibility(View.VISIBLE);
        mVideoView.setAlpha(0f);
        mVideoView.setSurfaceTextureListener(this);
        addView(mVideoView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
    }

    // ───────────────────────── playback ─────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        mSurface = st;
        if (mStopped || sGameRendering || mPrepareStarted) return;
        mPrepareStarted = true;
        startPlayer();
    }

    /** Build + arm the MediaPlayer against the bundled asset. */
    private void startPlayer() {
        if (mStopped || sGameRendering || mSurface == null) return;
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
            mp.setSurface(new Surface(mSurface));
            mp.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setVolume(0f, 0f);              // loading screen stays silent
            mp.setLooping(true);               // loop until the game renders
            mp.setOnPreparedListener(p -> {
                if (mStopped || sGameRendering || mPlayer != p) { releaseQuietly(p); return; }
                try { p.start(); } catch (Throwable t) { onPlaybackFailure(); }
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mVideoView != null) {
                    Log.i(TAG, "video: first frame rendered — revealing");
                    mHandler.removeCallbacks(mWatchdog);
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
        Log.w(TAG, "video: giving up — classic black stage");
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

    // ───────────────────────── stop / fallback ─────────────────────────

    /** Absolute fallback: tear down all video state, keep the static stage. */
    private synchronized void fallbackToStatic() {
        innerStop(true);
    }

    /** Immediate stop — used when the game render begins (req: no delay). */
    private synchronized void stopVideoNow() {
        if (mPlayer != null || mVideoView != null)
            Log.i(TAG, "video: stop (game render / detach) — releasing player");
        innerStop(true);
    }

    private void innerStop(boolean removeView) {
        mStopped = true;
        mHandler.removeCallbacks(mWatchdog);
        MediaPlayer p = mPlayer;
        mPlayer = null;
        releaseQuietly(p);
        if (removeView && mVideoView != null) {
            TextureView v = mVideoView;
            mVideoView = null;
            try {
                v.setSurfaceTextureListener(null);
                v.animate().cancel();
                removeView(v);
            } catch (Throwable ignored) {}
        }
    }

    private static void releaseQuietly(@Nullable MediaPlayer p) {
        if (p == null) return;
        try { p.setOnPreparedListener(null); p.setOnInfoListener(null); p.setOnErrorListener(null); } catch (Throwable ignored) {}
        try { p.stop(); } catch (Throwable ignored) {}
        try { p.reset(); } catch (Throwable ignored) {}
        try { p.release(); } catch (Throwable ignored) {}
    }
}
