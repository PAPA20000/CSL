package net.kdt.pojavlaunch.cursor;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.AbsSeekBar;
import android.widget.EditText;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * CursorRules — the editable rule engine that maps "what is under the
 * pointer" to a {@link CursorState}. Inspired by Zalith Launcher 2's
 * centralized cursor-shape state, but expressed as a fully user-editable,
 * persisted rule list instead of hard-coded mappings.
 *
 * Rules are evaluated in order; the first enabled rule whose matcher
 * applies wins. The list always ends with an ANY fallback rule so a view
 * can never resolve to "no state".
 */
public final class CursorRules {

    // Matcher codes
    public static final int MATCH_ANY = 0;
    public static final int MATCH_DISABLED = 1;
    public static final int MATCH_TEXT_INPUT = 2;
    public static final int MATCH_CLICKABLE = 3;
    public static final int MATCH_LONG_CLICKABLE = 4;
    public static final int MATCH_SEEKABLE = 5;
    public static final int MATCH_GLOBAL_BUSY = 6;
    public static final int MATCH_GLOBAL_DRAGGING = 7;

    public static final int[] ALL_MATCHERS = {
            MATCH_ANY, MATCH_DISABLED, MATCH_TEXT_INPUT, MATCH_CLICKABLE,
            MATCH_LONG_CLICKABLE, MATCH_SEEKABLE, MATCH_GLOBAL_BUSY, MATCH_GLOBAL_DRAGGING
    };

    private static final String KEY_RULES = "cse_rules_json";

    public static final class Rule {
        public int matcher;
        @NonNull public CursorState state;
        public boolean enabled = true;

        public Rule(int matcher, @NonNull CursorState state) {
            this.matcher = matcher;
            this.state = state;
        }

        @NonNull Rule copy() {
            Rule r = new Rule(matcher, state);
            r.enabled = enabled;
            return r;
        }
    }

    private static List<Rule> sCache;

    private CursorRules() {}

    /** Desktop-like defaults, evaluated top to bottom. */
    @NonNull
    private static List<Rule> defaults() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule(MATCH_DISABLED, CursorState.FORBIDDEN));
        rules.add(new Rule(MATCH_GLOBAL_DRAGGING, CursorState.MOVE));
        rules.add(new Rule(MATCH_GLOBAL_BUSY, CursorState.WORKING));
        rules.add(new Rule(MATCH_TEXT_INPUT, CursorState.IBEAM));
        rules.add(new Rule(MATCH_SEEKABLE, CursorState.HAND));
        rules.add(new Rule(MATCH_CLICKABLE, CursorState.HAND));
        rules.add(new Rule(MATCH_LONG_CLICKABLE, CursorState.HAND));
        rules.add(new Rule(MATCH_ANY, CursorState.ARROW));
        return rules;
    }

    @NonNull
    public static synchronized List<Rule> get() {
        if (sCache != null) return sCache;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        String json = prefs != null ? prefs.getString(KEY_RULES, null) : null;
        List<Rule> rules = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    CursorState state;
                    try {
                        state = CursorState.valueOf(o.optString("s", "ARROW"));
                    } catch (IllegalArgumentException iae) {
                        continue; // Unknown state name — skip the rule instead of dying.
                    }
                    Rule rule = new Rule(o.optInt("m", MATCH_ANY), state);
                    rule.enabled = o.optBoolean("e", true);
                    rules.add(rule);
                }
            } catch (JSONException ignored) {
                rules.clear();
            }
        }
        if (rules.isEmpty()) rules = defaults();
        ensureFallback(rules);
        sCache = rules;
        return sCache;
    }

    public static synchronized void save() {
        if (sCache == null) return;
        ensureFallback(sCache);
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        JSONArray array = new JSONArray();
        for (Rule rule : sCache) {
            try {
                JSONObject o = new JSONObject();
                o.put("m", rule.matcher);
                o.put("s", rule.state.name());
                o.put("e", rule.enabled);
                array.put(o);
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply();
    }

    public static synchronized void add(@NonNull Rule rule) {
        List<Rule> rules = get();
        // Insert before the terminal ANY fallback so the new rule is reachable.
        int insert = rules.size();
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).matcher == MATCH_ANY) insert = i;
            else break;
        }
        rules.add(insert, rule);
        save();
    }

    public static synchronized void remove(int index) {
        List<Rule> rules = get();
        if (index < 0 || index >= rules.size()) return;
        Rule victim = rules.get(index);
        if (victim.matcher == MATCH_ANY) return; // the fallback may never be deleted
        rules.remove(index);
        save();
    }

    public static synchronized void move(int index, int delta) {
        List<Rule> rules = get();
        int target = index + delta;
        if (index < 0 || index >= rules.size() || target < 0 || target >= rules.size()) return;
        Rule r = rules.remove(index);
        rules.add(target, r);
        save();
    }

    public static synchronized void update(int index, @NonNull CursorState state, boolean enabled) {
        List<Rule> rules = get();
        if (index < 0 || index >= rules.size()) return;
        Rule rule = rules.get(index);
        rule.state = state;
        rule.enabled = enabled;
        save();
    }

    public static synchronized void resetDefaults() {
        sCache = defaults();
        save();
    }

    public static synchronized void invalidateCache() {
        sCache = null;
    }

    /** Resolves the state for a hovered view using the current engine flags. */
    @NonNull
    public static CursorState resolve(@NonNull View view, boolean globalBusy, boolean globalDragging) {
        for (Rule rule : get()) {
            if (!rule.enabled) continue;
            if (matches(rule.matcher, view, globalBusy, globalDragging)) return rule.state;
        }
        return CursorState.ARROW;
    }

    public static boolean matches(int matcher, @NonNull View view, boolean globalBusy, boolean globalDragging) {
        switch (matcher) {
            case MATCH_DISABLED: return !view.isEnabled();
            case MATCH_TEXT_INPUT: return view instanceof EditText;
            case MATCH_CLICKABLE: return view.isClickable();
            case MATCH_LONG_CLICKABLE: return view.isLongClickable();
            case MATCH_SEEKABLE: return view instanceof AbsSeekBar;
            case MATCH_GLOBAL_BUSY: return globalBusy;
            case MATCH_GLOBAL_DRAGGING: return globalDragging;
            case MATCH_ANY:
            default: return true;
        }
    }

    /** The terminal ANY rule is sacred; restore the stock one if the user removed all fallbacks. */
    private static void ensureFallback(@NonNull List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.matcher == MATCH_ANY) return;
        }
        rules.add(new Rule(MATCH_ANY, CursorState.ARROW));
    }

    public static String matcherName(int matcher) {
        switch (matcher) {
            case MATCH_DISABLED: return "If view is disabled";
            case MATCH_TEXT_INPUT: return "If text input";
            case MATCH_CLICKABLE: return "If clickable (button)";
            case MATCH_LONG_CLICKABLE: return "If long-clickable";
            case MATCH_SEEKABLE: return "If slider / seekbar";
            case MATCH_GLOBAL_BUSY: return "While tasks running";
            case MATCH_GLOBAL_DRAGGING: return "While dragging";
            case MATCH_ANY:
            default: return "Any pointer (fallback)";
        }
    }
}
