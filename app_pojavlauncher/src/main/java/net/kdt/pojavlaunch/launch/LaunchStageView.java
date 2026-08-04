package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
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

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * LaunchStageView — the pre-render stage behind the game surface.
 *
 * Holds the classic (existing) static loading stage as its XML child and can
 * upgrade it to a STREAMED remote video when and only when:
 *   1. the user picked "Video Loading Screen" (loadingScreenStyle == "video")
 *   2. the remote config allows it (loadingVideo.enabled == true, url set)
 *   3. the device is online
 *
 * Playback notes (req-3/4/8 + cache):
 *  - MediaPlayer + TextureView. The FIRST contact with a URL is streamed
 *    (progressive playback); a validated throwaway copy is then kept in the
 *    app cache keyed by URL (see {@link LoadingVideoCache}): the same URL
 *    plays instantly from cache with zero re-download, a changed URL or a
 *    changed remote file re-fetches automatically. The video is never
 *    permanent and nothing ships inside the APK.
 *  - Every failure path (offline, prepare error, 7 s watchdog stall) silently
 *    falls back to the existing static stage — the user always gets a loading
 *    screen, never a blank panel.
 *  - Audio muted by design; the video loops until the game renders.
 *
 * Instant-stop (req-7): {@link #onGameRenderStarted()} is fired from
 * MainActivity's surface-ready signal — decoding stops on that exact frame.
 */
public class LaunchStageView extends FrameLayout
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "LaunchStageView";
    public static final String PREF_KEY_STYLE = "loadingScreenStyle";
    public static final String STYLE_BLACK = "black";
    public static final String STYLE_VIDEO = "video";
    private static final long WATCHDOG_MS = 7000L;

    // ── static launch-session registry ──
    private static volatile boolean sGameRendering;
    @Nullable private static WeakReference<LaunchStageView> sActive;

    private View mStaticStage;
    @Nullable private TextureView mVideoView;
    @Nullable private MediaPlayer mPlayer;
    @Nullable private String mVideoUrl;
    @Nullable private File mVideoFile;      // set when a validated cache hit exists
    @Nullable private SurfaceTexture mSurface;
    private boolean mStopped;
    private boolean mPrepareStarted;
    private boolean mRetriedWithStream;     // one cached→live retry, then black stage
    private boolean mRetriedWithCache;      // one live→cache retry, then black stage

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

        String url = RemoteConfigManager.getLoadingVideoUrl(getContext());
        Log.i(TAG, "bind: remote config url=" + url);
        if (url == null || url.trim().isEmpty()) {
            // Cold-start race: the launcher process fetch may not have
            // persisted yet. Fetch HERE (this process) and upgrade mid-load
            // if a fresh config lands before the game renders.
            RemoteConfigManager.refreshAsync(getContext(), this::onRemoteConfigUpdated);
            Log.i(TAG, "bind: classic stage for now (config absent — refresh kicked + rebind armed)");
            return;
        }
        boolean online;
        try { online = Tools.isOnline(getContext()); } catch (Throwable t) { online = false; }
        Log.i(TAG, "bind: online=" + online);
        if (!online) { Log.i(TAG, "bind: classic stage (offline)"); return; }

        mVideoUrl = url.trim();
        mRetriedWithStream = false;
        mRetriedWithCache = false;

        // Cache-first resolution: same URL → play from cache instantly;
        // miss/changed URL → stream now and prefetch in the background so the
        // next launch is instant and offline-tolerant.
        File cached = LoadingVideoCache.getValidCache(getContext(), mVideoUrl);
        if (cached != null) {
            mVideoFile = cached;
            Log.i(TAG, "bind: cache HIT (" + cached.length() + " bytes) — instant local play");
        } else {
            mVideoFile = null;
            Log.i(TAG, "bind: cache MISS — streaming now, prefetch in background");
            LoadingVideoCache.downloadAsync(getContext(), mVideoUrl);
        }

        // TextureView (not SurfaceView): obeys normal view compositing, so the
        // static stage stays visible underneath until the first video frame.
        mVideoView = new TextureView(getContext());
        // VISIBLE + alpha 0 — a GONE TextureView NEVER receives a SurfaceTexture,
        // so playback could never even start (this was the silent root cause of
        // "video never shows"). The fade below handles the reveal instead.
        mVideoView.setVisibility(View.VISIBLE);
        mVideoView.setAlpha(0f);
        mVideoView.setSurfaceTextureListener(this);
        addView(mVideoView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
    }

    /** Fresh config landed after we had fallen back — upgrade mid-load if still safe. */
    private void onRemoteConfigUpdated() {
        if (mStopped || sGameRendering || mVideoView != null) return;
        Log.i(TAG, "config updated during load — re-binding for video");
        bind();
    }

    // ───────────────────────── streaming ─────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        mSurface = st;
        if (mStopped || sGameRendering || mVideoUrl == null || mPrepareStarted) return;
        mPrepareStarted = true;
        startPlayer();
    }

    /** Build + arm the MediaPlayer against the current source (cache file or live URL). */
    private void startPlayer() {
        if (mStopped || sGameRendering || mVideoUrl == null || mSurface == null) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mPlayer = mp;
            Log.i(TAG, "video: preparing "
                    + (mVideoFile != null ? "cache-file (" + mVideoFile.length() + " bytes)"
                    : "stream " + mVideoUrl));
            if (mVideoFile != null) mp.setDataSource(mVideoFile.getAbsolutePath());
            else mp.setDataSource(getContext(), Uri.parse(mVideoUrl));
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
                Log.w(TAG, "video: onError what=" + what + " extra=" + extra
                        + " src=" + (mVideoFile != null ? "cache" : "stream"));
                onPlaybackFailure();
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "video prepare failed", t);
            onPlaybackFailure();
        }
    }

    /**
     * Unified fail-safe. A broken CACHED copy (corrupt, evicted mid-flight)
     * gets exactly one retry as a live stream — cache invalidated + refetched.
     * Every other failure drops straight to the existing static stage.
     * Never throws; the launcher can never crash from video.
     */
    private void onPlaybackFailure() {
        // A broken CACHED copy gets exactly one retry as a live stream…
        if (mVideoFile != null && !mRetriedWithStream) {
            mRetriedWithStream = true;
            Log.w(TAG, "video: cache unplayable — retrying live stream");
            try { LoadingVideoCache.invalidate(getContext()); } catch (Throwable ignored) {}
            mVideoFile = null;
            MediaPlayer old = mPlayer;
            mPlayer = null;
            releaseQuietly(old);
            LoadingVideoCache.downloadAsync(getContext(), mVideoUrl);
            startPlayer();
            return;
        }
        // …and a failed STREAM gets one retry from the cache if the background
        // prefetch has landed meanwhile (first-run / slow-network edge).
        if (mVideoFile == null && !mRetriedWithCache && mVideoUrl != null) {
            mRetriedWithCache = true;
            File hit = LoadingVideoCache.getValidCache(getContext(), mVideoUrl);
            if (hit != null) {
                Log.w(TAG, "video: stream failed — retrying from fresh cache");
                mVideoFile = hit;
                MediaPlayer old = mPlayer;
                mPlayer = null;
                releaseQuietly(old);
                startPlayer();
                return;
            }
        }
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

    /** Immediate stop — used when the game render begins (req-7). */
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
