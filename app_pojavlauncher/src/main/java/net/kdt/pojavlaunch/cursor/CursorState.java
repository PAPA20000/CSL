package net.kdt.pojavlaunch.cursor;

import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;

/**
 * CursorState — the semantic, desktop-style cursor states understood by the
 * CS Launcher cursor engine (modeled after the Zalith Launcher 2 cursor
 * bridge, adapted for Android Views instead of Compose).
 *
 * Each state carries the raw integer of the matching system pointer-icon
 * type (android.view.PointerIcon.TYPE_*). The raw ints are stored here
 * instead of the framework constants so that this enum stays loadable on
 * API < 24 devices where the PointerIcon class does not exist — every
 * PointerIcon call site is guarded by {@link CursorEngine#isSupported()}.
 */
public enum CursorState {

    ARROW(1000, R.string.cse_state_arrow),              // TYPE_ARROW
    HAND(1002, R.string.cse_state_hand),                // TYPE_HAND
    IBEAM(1008, R.string.cse_state_ibeam),              // TYPE_TEXT
    BUSY(1004, R.string.cse_state_busy),               // TYPE_WAIT
    WORKING(1004, R.string.cse_state_working),         // TYPE_WAIT (background work)
    FORBIDDEN(1012, R.string.cse_state_forbidden),     // TYPE_NO_DROP
    MOVE(1013, R.string.cse_state_move),               // TYPE_ALL_SCROLL
    RESIZE_NS(1015, R.string.cse_state_resize_ns),     // TYPE_VERTICAL_DOUBLE_ARROW
    RESIZE_EW(1014, R.string.cse_state_resize_ew),     // TYPE_HORIZONTAL_DOUBLE_ARROW
    RESIZE_ALL(1013, R.string.cse_state_resize_all),   // TYPE_ALL_SCROLL
    HELP(1001, R.string.cse_state_help),               // TYPE_CONTEXT_HELP
    WAIT(1004, R.string.cse_state_wait),               // TYPE_WAIT
    CROSSHAIR(1007, R.string.cse_state_crosshair);     // TYPE_CROSSHAIR

    /** Raw android.view.PointerIcon.TYPE_* value used as the system fallback. */
    public final int systemType;
    @StringRes public final int labelRes;

    CursorState(int systemType, @StringRes int labelRes) {
        this.systemType = systemType;
        this.labelRes = labelRes;
    }
}
