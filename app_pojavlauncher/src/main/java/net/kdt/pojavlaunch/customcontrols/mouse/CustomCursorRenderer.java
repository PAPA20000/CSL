package net.kdt.pojavlaunch.customcontrols.mouse;

import android.graphics.Bitmap;
import android.view.PointerIcon;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.cursor.CursorController;

/**
 * Compatibility facade for the Phase-4 cursor system.
 *
 * <p>Phase 3 shipped a single global cursor renderer (PNG/GIF + glow + scale)
 * that was called from {@code MinecraftGLSurface}, {@code Touchpad},
 * {@code CallbackBridge} and the old Cursor Studio. The Phase-4 engine lives
 * in {@link CursorController}; this class keeps the old static surface so
 * every existing call site keeps compiling and behaving, while delegating to
 * the new per-state system.</p>
 */
public final class CustomCursorRenderer {

    private CustomCursorRenderer() { }

    public static void startAnimation() {
        CursorController.startAnimation();
    }

    public static void stopAnimation() {
        CursorController.stopAnimation();
    }

    public static void updateCursorFrame() {
        CursorController.updateCursorFrame();
    }

    public static void reset() {
        CursorController.reset();
    }

    @Nullable
    public static PointerIcon getActivePointerIcon() {
        return CursorController.getActivePointerIcon();
    }

    @Nullable
    public static Bitmap getCurrentFrameBitmap() {
        return CursorController.getCurrentFrameBitmap();
    }
}
