package net.kdt.pojavlaunch.cursor;

import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.List;

/**
 * Launcher-UI hover tracker — makes every UI situation switch the cursor,
 * exactly like a desktop OS.
 *
 * <p>Attach it to a screen root ({@link #attach(View)}) and it watches
 * hover + touch events, hit-tests the deepest view under the pointer and
 * resolves a {@link CursorRules.Trigger} through the user-editable rules
 * table:</p>
 *
 * <pre>
 *   Button / clickable        → HAND
 *   Text field                → IBEAM
 *   Disabled view             → FORBIDDEN
 *   Pointer down / dragging   → MOVE
 *   Tagged "help" / "resize"  → HELP / RESIZE
 *   anything else             → ARROW
 * </pre>
 *
 * <p>Developers can force a trigger on any view with
 * {@code view.setTag(R.id.cursor_trigger_tag, "HELP")} (case-insensitive
 * {@link CursorRules.Trigger} name) — used by help chips and resize
 * handles.</p>
 *
 * <p>Hardware mice get a real {@code PointerIcon}; touch-only devices are
 * unaffected because there is no hover to track.</p>
 */
public final class CursorHoverTracker {

    private final Context mContext;
    private View mRoot;
    private View mLastView;
    private CursorState mLastState;
    private boolean mPointerDown;

    public CursorHoverTracker(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Attaches to a screen root; use {@link #detach()} to release. */
    public void attach(View root) {
        detach();
        mRoot = root;
        mRoot.setOnHoverListener(this::onHover);
        mRoot.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    mPointerDown = true;
                    if (mLastView != null) resolve(mLastView);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                    mPointerDown = false;
                    if (mLastView != null) resolve(mLastView);
                    break;
            }
            return false; // never consume — other listeners keep working
        });
    }

    public void detach() {
        if (mRoot == null) return;
        mRoot.setOnHoverListener(null);
        mRoot.setOnTouchListener(null);
        mRoot = null;
        mLastView = null;
        mLastState = null;
        CursorController.setUiCursor(null);
    }

    private boolean onHover(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (mRoot instanceof ViewGroup) {
                    View target = hitTest((ViewGroup) mRoot, event.getX(), event.getY());
                    if (target == null) target = mRoot;
                    mLastView = target;
                    resolve(target);
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                mLastView = null;
                mLastState = null;
                CursorController.setUiCursor(null);
                break;
        }
        return false;
    }

    private void resolve(View target) {
        if (mRoot == null || mRoot.getWindowToken() == null) return;

        List<CursorRules.CursorRule> rules = CursorStore.getRules(mContext);
        CursorRules.Trigger trigger = detectTrigger(target);
        CursorState state = CursorRules.resolve(rules, trigger);

        if (mPointerDown && trigger != CursorRules.Trigger.DISABLED) {
            // While the pointer is held down we are "dragging".
            state = CursorRules.resolve(rules, CursorRules.Trigger.DRAGGING);
        }

        if (state != mLastState) {
            mLastState = state;
            CursorController.setUiCursor(state);
            applyPointerIcon(target, state);
        }
    }

    /** Deepest visible child of {@code parent} that contains (x, y). */
    @Nullable
    public static View hitTest(@Nullable ViewGroup parent, float x, float y) {
        if (parent == null) return null;
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ViewGroup) {
                View deep = hitTest((ViewGroup) child, x, y);
                if (deep != null) return deep;
            }
            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private CursorRules.Trigger detectTrigger(View view) {
        if (view == null) return CursorRules.Trigger.DEFAULT;

        // Explicit tag override (e.g. help chips, resize handles).
        Object tag = view.getTag(R.id.cursor_trigger_tag);
        if (tag instanceof String) {
            try {
                return CursorRules.Trigger.valueOf(((String) tag).toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException ignored) { }
        }

        if (!view.isEnabled()) return CursorRules.Trigger.DISABLED;

        if (view instanceof EditText) return CursorRules.Trigger.TEXT_INPUT;
        if (view instanceof TextView && ((TextView) view).isTextSelectable()) {
            return CursorRules.Trigger.TEXT_INPUT;
        }

        if (view.isClickable() || view.isLongClickable() || view.hasOnClickListeners()) {
            return CursorRules.Trigger.BUTTON;
        }

        return CursorRules.Trigger.DEFAULT;
    }

    /** Sets a hardware PointerIcon from the state's custom art (API 24+). */
    private void applyPointerIcon(View view, CursorState state) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        CursorStyle style = CursorController.styleFor(state);
        android.graphics.Bitmap src = style.loadSource(mContext, state);
        if (src == null) return;
        CursorStyle.Processed p = style.process(src, state);
        if (p == null) return;
        try {
            view.setPointerIcon(PointerIcon.create(p.bitmap, p.hotspotX, p.hotspotY));
        } catch (Throwable ignored) { }
    }
}
