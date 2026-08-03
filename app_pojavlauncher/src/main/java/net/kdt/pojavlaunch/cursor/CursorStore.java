package net.kdt.pojavlaunch.cursor;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for per-state cursor styles + the rule table.
 *
 * <p>Styles live as JSON inside SharedPreferences ({@code cursor_styles_v2}),
 * a map of state-key → {@link CursorStyle}. The rule table is a JSON list in
 * {@code cursor_rules_v1}. Everything is Gson-serialised, so adding fields
 * later stays backward compatible (missing fields keep their defaults).</p>
 *
 * <p>Legacy single-cursor prefs ({@code custom_cursor_*}, written by the old
 * Cursor Studio) are honoured live for the ARROW state until the user edits
 * styles in the new Studio for the first time — see {@link #userEdited()}.</p>
 */
public final class CursorStore {

    private static final String PREF_NAME = "cs_cursor_system";
    private static final String KEY_STYLES = "cursor_styles_v2";
    private static final String KEY_RULES = "cursor_rules_v1";
    private static final String KEY_USER_EDITED = "cursor_styles_v2_user_edited";
    private static final String KEY_ENABLED = "cursor_system_enabled";

    private static final Gson GSON = new Gson();

    private static SharedPreferences sPrefs;
    private static final Map<String, CursorStyle> sCache = new HashMap<>();
    private static boolean sLoaded;

    private CursorStore() { }

    private static SharedPreferences prefs(Context ctx) {
        if (sPrefs == null) {
            sPrefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
        return sPrefs;
    }

    /** True once the user has saved a style through the new Studio. */
    public static boolean userEdited(Context ctx) {
        return prefs(ctx).getBoolean(KEY_USER_EDITED, false);
    }

    public static void markUserEdited(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_USER_EDITED, true).apply();
    }

    public static boolean isSystemEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setSystemEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static void ensureLoaded(Context ctx) {
        if (sLoaded) return;
        sLoaded = true;
        String json = prefs(ctx).getString(KEY_STYLES, null);
        if (json == null) return;
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            for (CursorState state : CursorState.values()) {
                if (root.has(state.key)) {
                    CursorStyle style = GSON.fromJson(
                            root.get(state.key).toString(), CursorStyle.class);
                    if (style != null) sCache.put(state.key, style);
                }
            }
        } catch (Throwable t) {
            sCache.clear();
        }
    }

    /** The persisted style for a state (never null — falls back to default). */
    @NonNull
    public static CursorStyle getStyle(Context ctx, CursorState state) {
        ensureLoaded(ctx);
        CursorStyle cached = sCache.get(state.key);
        if (cached != null) return cached;
        return CursorStyle.defaultFor(state);
    }

    /** Saves a style for a state, marks the system as user-edited and persists. */
    public static void setStyle(Context ctx, CursorState state, CursorStyle style) {
        ensureLoaded(ctx);
        sCache.put(state.key, style);
        markUserEdited(ctx);
        persist(ctx);
    }

    /** Restores a state to its built-in defaults. */
    public static void resetStyle(Context ctx, CursorState state) {
        ensureLoaded(ctx);
        sCache.remove(state.key);
        persist(ctx);
    }

    public static void resetAllStyles(Context ctx) {
        ensureLoaded(ctx);
        sCache.clear();
        persist(ctx);
    }

    private static void persist(Context ctx) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        for (CursorState state : CursorState.values()) {
            CursorStyle style = sCache.get(state.key);
            if (style != null) {
                root.add(state.key, GSON.toJsonTree(style));
            }
        }
        prefs(ctx).edit().putString(KEY_STYLES, root.toString()).apply();
    }

    // ─────────────────────────── RULES ───────────────────────────

    /** Loads the rule table; falls back to {@link CursorRules#defaults()}. */
    @NonNull
    public static List<CursorRules.CursorRule> getRules(Context ctx) {
        String json = prefs(ctx).getString(KEY_RULES, null);
        if (json == null) return CursorRules.defaults();
        try {
            List<CursorRules.CursorRule> rules = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<ArrayList<CursorRules.CursorRule>>() { }.getType());
            if (rules != null && !rules.isEmpty()) return rules;
        } catch (Throwable ignored) { }
        return CursorRules.defaults();
    }

    public static void saveRules(Context ctx, List<CursorRules.CursorRule> rules) {
        prefs(ctx).edit().putString(KEY_RULES, GSON.toJson(rules)).apply();
    }
}
