package net.kdt.pojavlaunch.cursor;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * User-editable cursor rules — the "IF <situation> THEN <cursor>" table.
 *
 * <p>Nothing in the launcher hardcodes which cursor a situation shows; every
 * trigger is a row the user can retarget to any {@link CursorState} (so
 * "Instead of Hand → use Sword cursor" is simply pointing the BUTTON rule at
 * a state whose art is a sword), toggle on/off, or reset to defaults.</p>
 */
public final class CursorRules {

    /** UI situations the launcher can detect. */
    public enum Trigger {
        BUTTON,
        TEXT_INPUT,
        DISABLED,
        DRAGGING,
        LOADING,
        HELP,
        RESIZE,
        DEFAULT
    }

    /** One editable rule row. */
    public static final class CursorRule {
        public Trigger trigger;
        public CursorState target;
        public boolean enabled = true;

        public CursorRule() { }

        public CursorRule(Trigger trigger, CursorState target) {
            this.trigger = trigger;
            this.target = target;
        }
    }

    private CursorRules() { }

    @NonNull
    public static List<CursorRule> defaults() {
        List<CursorRule> rules = new ArrayList<>();
        rules.add(new CursorRule(Trigger.BUTTON, CursorState.HAND));
        rules.add(new CursorRule(Trigger.TEXT_INPUT, CursorState.IBEAM));
        rules.add(new CursorRule(Trigger.DISABLED, CursorState.FORBIDDEN));
        rules.add(new CursorRule(Trigger.DRAGGING, CursorState.MOVE));
        rules.add(new CursorRule(Trigger.LOADING, CursorState.LOADING));
        rules.add(new CursorRule(Trigger.HELP, CursorState.HELP));
        rules.add(new CursorRule(Trigger.RESIZE, CursorState.RESIZE_EW));
        rules.add(new CursorRule(Trigger.DEFAULT, CursorState.ARROW));
        return rules;
    }

    /** Resolves the cursor state for a trigger using the given rule table. */
    @NonNull
    public static CursorState resolve(List<CursorRule> rules, Trigger trigger) {
        if (rules != null) {
            for (CursorRule rule : rules) {
                if (rule != null && rule.enabled && rule.trigger == trigger && rule.target != null) {
                    return rule.target;
                }
            }
        }
        // Built-in fallback so a corrupted table never leaves us cursor-less.
        switch (trigger) {
            case BUTTON:    return CursorState.HAND;
            case TEXT_INPUT:return CursorState.IBEAM;
            case DISABLED:  return CursorState.FORBIDDEN;
            case DRAGGING:  return CursorState.MOVE;
            case LOADING:   return CursorState.LOADING;
            case HELP:      return CursorState.HELP;
            case RESIZE:    return CursorState.RESIZE_EW;
            case DEFAULT:
            default:        return CursorState.ARROW;
        }
    }
}
