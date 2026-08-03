package net.kdt.pojavlaunch.cursor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.Build;
import android.util.Log;
import android.view.Choreographer;
import android.view.PointerIcon;
import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The heart of the Phase-4 cursor system.
 *
 * <p>Resolves the <b>active</b> {@link CursorState} from two sources and
 * renders it everywhere the user can see a pointer:</p>
 * <ol>
 *   <li><b>Game state</b> — the Minecraft client switches GLFW standard
 *       cursors (button → hand, chat → I-beam, resize, forbidden …). Those
 *       shapes arrive via {@link CallbackBridge#glfwSetCursor} +
 *       {@link CursorRegistry} and are mapped with
 *       {@link CursorState#fromGlfwShape}.</li>
 *   <li><b>Launcher UI state</b> — {@link CursorHoverTracker} feeds detected
 *       situations (button / text field / disabled / dragging …) resolved
 *       through the user-editable {@link CursorRules} table.</li>
 * </ol>
 *
 * <p>The active state's {@link CursorStyle} is processed through the full
 * pipeline (scale / rotation / tint / shadow / border / glow / opacity) and
 * pushed to (a) the GL surface view as an {@code android.view.PointerIcon}
 * for hardware mice, and (b) the virtual {@code Touchpad} bitmap. Animated
 * GIF cursors run a 60 fps frame loop driven by {@link Choreographer}.</p>
 */
public final class CursorController {

    private static final String TAG = "CursorController";

    /** Rendered frame + hotspot for the Touchpad / PointerIcon. */
    public static final class Frame {
        public final Bitmap bitmap;
        public final int hotspotX;
        public final int hotspotY;
        Frame(Bitmap bitmap, int hx, int hy) {
            this.bitmap = bitmap;
            this.hotspotX = hx;
            this.hotspotY = hy;
        }
    }

    private static Context sContext;
    private static CursorState sGameState = CursorState.ARROW;
    private static CursorState sUiState = CursorState.ARROW;
    private static boolean sUiOverride;
    private static CursorState sActive = CursorState.ARROW;

    // Processed frame cache (state → rendered static frame)
    private static final Map<CursorState, Frame> sFrameCache = new HashMap<>();
    // GIF playback state for the active animated state
    private static Movie sMovie;
    private static Bitmap sGifCanvasBitmap;
    private static Canvas sGifCanvas;
    private static long sAnimStart;
    private static boolean sAnimRunning;
    private static CursorState sAnimState;
    private static boolean sDirty = true;

    private static final Choreographer.FrameCallback sFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!sAnimRunning) return;
            updateFrame();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private CursorController() { }

    /** Must be called once with an application context (PojavApplication). */
    public static void attach(Context ctx) {
        sContext = ctx.getApplicationContext();
    }

    public static boolean isAvailable() {
        return sContext != null;
    }

    // ─────────────────────── STATE SOURCES ───────────────────────

    /** Called from the game bridge (any thread) when the game switches cursor. */
    public static void onGameCursorShape(final int glfwShape) {
        final CursorState next = CursorState.fromGlfwShape(glfwShape);
        if (next == sGameState) return;
        sGameState = next;
        Tools.MAIN_HANDLER.post(() -> {
            // 16 ms debounce (Zalith-style) so flickering shapes don't thrash
            Choreographer.getInstance().postFrameCallbackDelayed(l -> {
                if (sGameState != next) return;
                resolveActive();
            }, 16);
        });
    }

    /** Launcher UI state from hover rules; pass null to clear the override. */
    public static void setUiCursor(@Nullable CursorState state) {
        sUiState = state != null ? state : CursorState.ARROW;
        sUiOverride = state != null;
        resolveActive();
    }

    private static void resolveActive() {
        CursorState next = sUiOverride ? sUiState : sGameState;
        if (next == sActive && !sDirty) return;
        sActive = next;
        sDirty = false;
        onActiveStateChanged();
    }

    private static void onActiveStateChanged() {
        stopGif();
        sFrameCache.remove(sActive);
        if (styleFor(sActive).isAnimated()) startGif();
        updateFrame();
    }

    // ─────────────────────── STYLE / LEGACY ───────────────────────

    /** Style for a state, honouring the legacy single-cursor prefs for ARROW. */
    public static CursorStyle styleFor(CursorState state) {
        if (state == CursorState.ARROW && !CursorStore.userEdited(sContext)
                && LauncherPreferences.PREF_CUSTOM_CURSOR_ENABLED
                && LauncherPreferences.PREF_CUSTOM_CURSOR_PATH != null) {
            // Live legacy override: old studio writes custom_cursor_* prefs.
            CursorStyle legacy = CursorStyle.defaultFor(CursorState.ARROW);
            legacy.useCustom = true;
            legacy.path = LauncherPreferences.PREF_CUSTOM_CURSOR_PATH;
            legacy.scale = Math.max(0.25f, LauncherPreferences.PREF_CUSTOM_CURSOR_SCALE / 100f);
            legacy.opacity = LauncherPreferences.PREF_CUSTOM_CURSOR_OPACITY;
            legacy.glowRadius = LauncherPreferences.PREF_CUSTOM_CURSOR_GLOW_RADIUS;
            legacy.glowColor = LauncherPreferences.PREF_CUSTOM_CURSOR_GLOW_COLOR;
            legacy.hotspotX = LauncherPreferences.DEFAULT_PREF
                    .getInt("custom_cursor_hotspot_x", CursorState.ARROW.defaultHotspotX);
            legacy.hotspotY = LauncherPreferences.DEFAULT_PREF
                    .getInt("custom_cursor_hotspot_y", CursorState.ARROW.defaultHotspotY);
            return legacy;
        }
        return CursorStore.getStyle(sContext, state);
    }

    // ─────────────────────── RENDERING ───────────────────────

    /**
     * The processed frame for the active state (animating GIFs advance
     * automatically). Returns null when the system is disabled.
     */
    @Nullable
    public static Frame getActiveFrame() {
        if (sContext == null || !CursorStore.isSystemEnabled(sContext)) return null;
        Frame cached = sFrameCache.get(sActive);
        if (cached != null && !isActiveGif()) return cached;

        // Animated path: draw the current Movie frame, then pipeline it.
        if (sMovie != null && sGifCanvasBitmap != null) {
            int duration = sMovie.duration();
            if (duration <= 0) duration = 1000;
            int rel = (int) ((System.currentTimeMillis() - sAnimStart) % duration);
            sMovie.setTime(rel);
            sGifCanvasBitmap.eraseColor(0);
            sMovie.draw(sGifCanvas, 0, 0);
            CursorStyle style = styleFor(sActive);
            CursorStyle.Processed p = style.process(sGifCanvasBitmap, sActive);
            return p != null ? new Frame(p.bitmap, p.hotspotX, p.hotspotY) : null;
        }

        Bitmap src = styleFor(sActive).loadSource(sContext, sActive);
        if (src == null) return null;
        CursorStyle.Processed p = styleFor(sActive).process(src, sActive);
        if (p == null) return null;
        Frame frame = new Frame(p.bitmap, p.hotspotX, p.hotspotY);
        if (!isActiveGif()) sFrameCache.put(sActive, frame);
        return frame;
    }

    /** Bitmap for the Touchpad (virtual cursor). */
    @Nullable
    public static Bitmap getCurrentFrameBitmap() {
        Frame f = getActiveFrame();
        return f != null ? f.bitmap : null;
    }

    /** Current hotspot for the Touchpad (in the rendered bitmap's space). */
    public static int getCurrentHotspotX() {
        Frame f = getActiveFrame();
        return f != null ? f.hotspotX : 0;
    }

    public static int getCurrentHotspotY() {
        Frame f = getActiveFrame();
        return f != null ? f.hotspotY : 0;
    }

    private static boolean isActiveGif() {
        return sMovie != null && sAnimState == sActive;
    }

    // ─────────────────────── POINTER ICON (hardware mouse) ───────────────────────

    /** Builds the PointerIcon for the current active frame (API 24+). */
    @Nullable
    public static PointerIcon getActivePointerIcon() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        Frame f = getActiveFrame();
        if (f == null || f.bitmap == null) return null;
        try {
            return PointerIcon.create(f.bitmap, f.hotspotX, f.hotspotY);
        } catch (Throwable t) {
            Log.e(TAG, "PointerIcon.create failed", t);
            return null;
        }
    }

    /** Applies the active pointer icon to the GL surface view. */
    public static void updateCursorFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || sContext == null) return;
        MinecraftGLSurface surface = CallbackBridge.getMinecraftGLSurface();
        if (surface == null) return;
        View target = surface.getSurfaceView() != null ? surface.getSurfaceView() : surface;
        PointerIcon icon = getActivePointerIcon();
        if (icon == null) return;
        try {
            if (target.getPointerIcon() != icon) target.setPointerIcon(icon);
        } catch (Throwable t) {
            Log.e(TAG, "setPointerIcon failed", t);
        }
    }

    // ─────────────────────── GIF LOOP ───────────────────────

    /** Starts the frame loop; no-op unless the active style is an animated GIF. */
    public static void startAnimation() {
        if (sContext == null) return;
        CursorStyle style = styleFor(sActive);
        if (style.isAnimated()) startGif();
    }

    public static void stopAnimation() {
        stopGif();
    }

    private static void startGif() {
        if (sAnimRunning) return;
        CursorStyle style = styleFor(sActive);
        if (!style.isAnimated() || style.path == null) return;
        File f = new File(style.path);
        if (!f.exists()) return;
        try {
            sMovie = Movie.decodeFile(style.path);
            if (sMovie == null) return;
            int w = Math.max(1, sMovie.width());
            int h = Math.max(1, sMovie.height());
            sGifCanvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            sGifCanvas = new Canvas(sGifCanvasBitmap);
            sAnimState = sActive;
            sAnimStart = System.currentTimeMillis();
            sAnimRunning = true;
            Choreographer.getInstance().postFrameCallback(sFrameCallback);
        } catch (Throwable t) {
            Log.e(TAG, "GIF decode failed", t);
            stopGif();
        }
    }

    private static void stopGif() {
        sAnimRunning = false;
        sAnimState = null;
        if (sMovie != null) sMovie = null;
        if (sGifCanvasBitmap != null) {
            sGifCanvasBitmap.recycle();
            sGifCanvasBitmap = null;
        }
        sGifCanvas = null;
    }

    private static void updateFrame() {
        // Touchpad redraw + PointerIcon refresh happen on the UI thread.
        Tools.MAIN_HANDLER.post(() -> {
            updateCursorFrame();
            net.kdt.pojavlaunch.customcontrols.mouse.Touchpad touchpad =
                    net.kdt.pojavlaunch.MainActivity.touchpad;
            if (touchpad != null) touchpad.postInvalidate();
        });
    }

    // ─────────────────────── LIFE-CYCLE ───────────────────────

    /** Drops every cached frame so the next render re-reads the styles. */
    public static void reset() {
        stopGif();
        sFrameCache.clear();
        sDirty = true;
        resolveActive();
    }

    /** Full teardown (game end / process death). */
    public static void destroy() {
        stopGif();
        sFrameCache.clear();
        sGameState = CursorState.ARROW;
        sUiState = CursorState.ARROW;
        sUiOverride = false;
        sActive = CursorState.ARROW;
        sDirty = true;
        CursorRegistry.clear();
    }
}
