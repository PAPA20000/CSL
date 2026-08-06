package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.utils.FpsCounter;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * LaunchStageView — the pre-render stage + optional user GIF.
 *
 * v5 (user directive): the bundled loading VIDEO is GONE, asset and player
 * both. The launch stage is the classic BLACK screen — exactly as before —
 * with one new degree of freedom: in Settings → Launcher Settings the user
 * can import ANY GIF, which then plays in this stage in place of the pure
 * black, full-screen & fit-center on a black canvas.
 *
 * Everything else stays byte-for-byte the same release contract the video
 * honored, so "sab kuchh same rahega":
 *
 *  • The GIF lives in @id/video_stage_host — ABOVE the game surface, below
 *    the log console, touch-transparent.
 *  • It paints across the WHOLE boot (attach → JVM start → MC init) and is
 *    removed exactly on the FIRST PRESENTED FRAME, detected via the native
 *    present counter (FpsCounter.getTotalPresents, 250 ms latch).
 *  • 7 s load watchdog (bad/un-decodable GIF → black stage, never a crash)
 *    and an 18 s hard cap for bridges without a Java-visible present hook
 *    (zink/vulkan path) — the game can NEVER be held up by the stage.
 *  • The game's surface-ready signal only ARMS the first-frame watch; it
 *    never gates the stage (root-cause ① of the video era — kept fixed).
 *
 * State is just ONE file: files/launch_gif.gif. No style pref, no player.
 * The file is imported (stream-copied + magic-byte validated) by the
 * settings screen; LaunchStageView never touches storage beyond reading it.
 */
public class LaunchStageView extends FrameLayout {

    private static final String TAG = "LaunchStageView";
    /** User-imported GIF living in the app's private files dir. */
    public static final String GIF_FILE_NAME = "launch_gif.gif";
    private static final long LOAD_WATCHDOG_MS = 7000L;
    /** Safety cap for bridges without a present hook (zink/vulkan). */
    private static final long FIRST_FRAME_CAP_MS = 18000L;
    private static final long FIRST_FRAME_POLL_MS = 250L;

    // ── static launch-session registry ──
    @Nullable private static WeakReference<LaunchStageView> sActive;

    /** Absolute path of the user-imported launch GIF for the given context. */
    @NonNull
    public static File gifFileFor(@NonNull Context ctx) {
        return new File(ctx.getFilesDir(), GIF_FILE_NAME);
    }

    private View mStaticStage;
    @Nullable private GifStageView mGifView;
    @Nullable private ViewGroup mGifHost;
    private boolean mStopped;
    private long mPresentBaseline = -1;  // armed at surface-ready
    private boolean mFirstFrameWatch;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLoadWatchdog = () -> {
        Log.w(TAG, "gif: load watchdog — classic black stage");
        dropGifStage();
    };
    private final Runnable mFirstFrameCap = () -> {
        Log.i(TAG, "gif: first-frame cap reached — dropping stage");
        dropGifStage();
    };
    private final Runnable mFirstFramePoll = new Runnable() {
        @Override public void run() {
            if (mStopped) return;
            long total = FpsCounter.getTotalPresents();
            if (mPresentBaseline < 0) mPresentBaseline = total; // native late — keep baselining
            if (total >= 0 && mPresentBaseline >= 0 && total > mPresentBaseline) {
                Log.i(TAG, "gif: FIRST FRAME presented — dropping stage now");
                dropGifStage();
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
     * watch — the stage keeps painting through JVM start + MC init and is
     * removed exactly when a real frame presents.
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
        mStopped = true;
        mHandler.removeCallbacks(mLoadWatchdog);
        mHandler.removeCallbacks(mFirstFramePoll);
        mHandler.removeCallbacks(mFirstFrameCap);
        releaseGifView();
        if (sActive != null && sActive.get() == this) sActive = null;
        super.onDetachedFromWindow();
    }

    // ───────────────────────── bind decision ─────────────────────────

    private void bind() {
        if (mStaticStage == null && getChildCount() > 0) mStaticStage = getChildAt(0);

        // CS Customisation: launch stage background color (default pure black).
        try {
            int stageColor = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF
                    .getInt("launch_stage_color", 0xFF000000);
            setBackgroundColor(stageColor);
            if (mStaticStage != null) mStaticStage.setBackgroundColor(stageColor);
        } catch (Throwable ignored) {}

        // CS Customisation: user can force the classic black stage even when a
        // GIF file exists (Settings → Launcher Customisation → Launch Screen).
        String style = "gif";
        try {
            style = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF
                    .getString("launch_screen_style", "gif");
        } catch (Throwable ignored) {}
        if ("black".equals(style)) {
            Log.i(TAG, "bind: launch screen style = black (user choice) — skipping GIF");
            return;
        }

        // No GIF imported → classic black stage, absolutely nothing to do.
        File gif = gifFileFor(getContext());
        if (!gif.isFile() || gif.length() <= 0) {
            Log.i(TAG, "bind: no launch GIF imported — classic black stage");
            return;
        }
        Log.i(TAG, "bind: user GIF found (" + gif.length() + " bytes) — staging");

        // Attach above the game surface via the dedicated host (video-era fix).
        ViewGroup host = null;
        try {
            View root = getRootView();
            if (root != null) host = root.findViewById(R.id.video_stage_host);
        } catch (Throwable ignored) {}
        mGifHost = host != null ? host : this; // defensive fallback

        GifStageView gifView = new GifStageView(getContext());
        mGifView = gifView;
        mGifHost.addView(gifView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mHandler.postDelayed(mLoadWatchdog, LOAD_WATCHDOG_MS);
        gifView.load(gif, new GifStageView.LoadCallback() {
            @Override public void onReady() {
                Log.i(TAG, "gif: first painted frame on screen — stage live");
                mHandler.removeCallbacks(mLoadWatchdog);
            }
            @Override public void onFailed() {
                Log.w(TAG, "gif: decode failed — classic black stage");
                postDropIfSame(gifView);
            }
        });
    }

    /** Drop the stage only if this exact view is still the active one. */
    private void postDropIfSame(@NonNull GifStageView v) {
        v.post(() -> { if (mGifView == v) dropGifStage(); });
    }

    // ───────────────────────── first-frame release ─────────────────────────

    /** Armed at surface-ready; drops the GIF on the FIRST presented frame. */
    private synchronized void armFirstFrameWatch() {
        if (mStopped || mGifView == null || mFirstFrameWatch) return;
        mFirstFrameWatch = true;
        mPresentBaseline = FpsCounter.getTotalPresents();
        Log.i(TAG, "gif: first-frame watch armed (baseline=" + mPresentBaseline + ", cap=" + FIRST_FRAME_CAP_MS + "ms)");
        mHandler.postDelayed(mFirstFramePoll, FIRST_FRAME_POLL_MS);
        mHandler.postDelayed(mFirstFrameCap, FIRST_FRAME_CAP_MS); // zink/vulkan safety
    }

    // ───────────────────────── stop ─────────────────────────

    /** Immediate stage drop — first frame / decode failure / watchdog / cap. */
    private synchronized void dropGifStage() {
        if (mGifView != null) Log.i(TAG, "gif: dropping stage — black game behind it");
        mHandler.removeCallbacks(mLoadWatchdog);
        mHandler.removeCallbacks(mFirstFramePoll);
        mHandler.removeCallbacks(mFirstFrameCap);
        releaseGifView();
    }

    private void releaseGifView() {
        GifStageView v = mGifView;
        mGifView = null;
        if (v != null) {
            try {
                v.animate().cancel();
                v.shutdown();
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent != null) parent.removeView(v);
            } catch (Throwable ignored) {}
        }
        mGifHost = null;
    }
}
