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
 * Streaming notes (req-3/4/8):
 *  - MediaPlayer + TextureView progressive playback: the MP4 is streamed with
 *    no permanent local copy (framework in-memory/throwaway buffering only),
 *    so nothing is ever downloaded to the device permanently and nothing ships
 *    inside the APK.
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
    private boolean mStopped;
    private boolean mPrepareStarted;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdog = this::fallbackToStatic;

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

        String mode;
        try {
            mode = LauncherPreferences.DEFAULT_PREF != null
                    ? LauncherPreferences.DEFAULT_PREF.getString(PREF_KEY_STYLE, STYLE_BLACK)
                    : STYLE_BLACK;
        } catch (Throwable t) { return; }
        if (!STYLE_VIDEO.equals(mode)) return;          // user keeps black screen

        String url = RemoteConfigManager.getLoadingVideoUrl(getContext());
        if (url == null || url.trim().isEmpty()) return; // remotely disabled
        try { if (!Tools.isOnline(getContext())) return; } catch (Throwable ignored) {}

        mVideoUrl = url.trim();

        // TextureView (not SurfaceView): obeys normal view compositing, so the
        // static stage stays visible underneath until the first video frame.
        mVideoView = new TextureView(getContext());
        mVideoView.setVisibility(View.GONE);
        mVideoView.setSurfaceTextureListener(this);
        addView(mVideoView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
    }

    // ───────────────────────── streaming ─────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        if (mStopped || sGameRendering || mVideoUrl == null || mPrepareStarted) return;
        mPrepareStarted = true;
        try {
            MediaPlayer mp = new MediaPlayer();
            mPlayer = mp;
            mp.setDataSource(getContext(), Uri.parse(mVideoUrl));
            mp.setSurface(new Surface(st));
            mp.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setVolume(0f, 0f);              // loading screen stays silent
            mp.setLooping(true);               // loop until the game renders
            mp.setOnPreparedListener(p -> {
                if (mStopped || sGameRendering || mPlayer != p) { releaseQuietly(p); return; }
                try { p.start(); } catch (Throwable t) { fallbackToStatic(); }
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mVideoView != null) {
                    mHandler.removeCallbacks(mWatchdog);
                    mVideoView.setVisibility(View.VISIBLE);
                    mVideoView.setAlpha(0f);
                    mVideoView.animate().alpha(1f).setDuration(260).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> { fallbackToStatic(); return true; });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "video prepare failed", t);
            fallbackToStatic();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
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
