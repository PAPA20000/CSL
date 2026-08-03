package net.kdt.pojavlaunch.cursor;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;

/**
 * Every distinct cursor situation the launcher + in-game UI can be in.
 *
 * <p>Each state carries the GLFW standard-shape constant it maps from
 * (when the game itself switches cursors), the default drawable used when
 * the user has not customised this state, a default hotspot and a stable
 * storage key.</p>
 *
 * <p>Two states ({@link #LOADING} and {@link #HELP}) have no GLFW shape —
 * they are driven purely by launcher rules.</p>
 */
public enum CursorState {

    ARROW   (0x36001, R.drawable.ic_mouse_pointer,       2,  2, "arrow",   R.string.cursor_state_arrow),
    IBEAM   (0x36002, R.drawable.cursor_ibeam,           10, 20, "ibeam",   R.string.cursor_state_ibeam),
    CROSSHAIR(0x36003, R.drawable.cursor_crosshair,      24, 24, "crosshair", R.string.cursor_state_crosshair),
    HAND    (0x36004, R.drawable.cursor_hand,            8,  6, "hand",    R.string.cursor_state_hand),
    RESIZE_EW (0x36005, R.drawable.cursor_resize_ew,     24, 24, "resize_ew", R.string.cursor_state_resize_ew),
    RESIZE_NS (0x36006, R.drawable.cursor_resize_ns,     24, 24, "resize_ns", R.string.cursor_state_resize_ns),
    RESIZE_NWSE (0x36007, R.drawable.cursor_resize_nwse, 24, 24, "resize_nwse", R.string.cursor_state_resize_nwse),
    RESIZE_NESW (0x36008, R.drawable.cursor_resize_nesw, 24, 24, "resize_nesw", R.string.cursor_state_resize_nesw),
    MOVE    (0x36009, R.drawable.cursor_move,            24, 24, "move",   R.string.cursor_state_move),
    FORBIDDEN (0x3600A, R.drawable.cursor_forbidden,     24, 24, "forbidden", R.string.cursor_state_forbidden),

    /** No GLFW equivalent — launcher rule "Loading". */
    LOADING (0, R.drawable.cursor_loading,               16, 16, "loading", R.string.cursor_state_loading),
    /** No GLFW equivalent — launcher rule "Help". */
    HELP    (0, R.drawable.cursor_help,                  16, 16, "help",   R.string.cursor_state_help),
    /** I-beam used for pure text-only fields. */
    TEXT    (0, R.drawable.cursor_ibeam,                 10, 20, "text",   R.string.cursor_state_text);

    /** GLFW standard cursor constant (0 when the game never emits it). */
    public final int glfwShape;
    @DrawableRes public final int defaultDrawable;
    /** Default hotspot (pixels inside the 48×48 default art). */
    public final int defaultHotspotX;
    public final int defaultHotspotY;
    /** Stable storage key. */
    public final String key;
    @StringRes public final int labelRes;

    CursorState(int glfwShape, int defaultDrawable, int hx, int hy, String key, int labelRes) {
        this.glfwShape = glfwShape;
        this.defaultDrawable = defaultDrawable;
        this.defaultHotspotX = hx;
        this.defaultHotspotY = hy;
        this.key = key;
        this.labelRes = labelRes;
    }

    public static CursorState fromGlfwShape(int shape) {
        for (CursorState s : values()) {
            if (s.glfwShape != 0 && s.glfwShape == shape) return s;
        }
        return ARROW;
    }

    public static CursorState fromKey(String key) {
        if (key == null) return ARROW;
        for (CursorState s : values()) {
            if (s.key.equals(key)) return s;
        }
        return ARROW;
    }
}
