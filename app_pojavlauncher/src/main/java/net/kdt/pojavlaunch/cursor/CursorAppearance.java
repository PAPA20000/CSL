package net.kdt.pojavlaunch.cursor;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * CursorAppearance — the full per-state customization model of the cursor
 * engine. Every {@link CursorState} owns one of these, persisted as JSON in
 * the launcher preferences under {@code cse_app_<STATE>}.
 *
 * Nothing here is hard-coded to a specific visual: a state can stay on its
 * system pointer icon, or be backed by an imported PNG which then flows
 * through the complete style pipeline (scale, rotation, opacity, tint,
 * border, glow, shadow, hotspot).
 */
public class CursorAppearance {

    public boolean customBitmap;             // false = use the system pointer icon for this state
    @Nullable public String bitmapPath;      // PNG under Tools.DIR_CURSORS (cse_<STATE>.png)
    public int scalePercent = 100;           // 25..300
    public int rotationDeg = 0;              // -180..180
    public int opacityPercent = 100;         // 0..100
    public int tintColor = 0;                // 0 = keep original colors
    public int glowColor = 0;                // 0 = no glow
    public int glowRadius = 0;               // 0..24 (dips)
    public int borderColor = 0;              // 0 = no border
    public int borderWidth = 0;              // 0..6 (dips)
    public int shadowRadius = 0;             // 0..12 (dips)
    public int hotspotX = -1;                // px in the source bitmap; -1 = auto (center)
    public int hotspotY = -1;                // px in the source bitmap; -1 = auto (center)

    private static final String PREFIX = "cse_app_";

    @NonNull
    public static CursorAppearance load(@NonNull CursorState state) {
        CursorAppearance appearance = new CursorAppearance();
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return appearance;
        String json = prefs.getString(PREFIX + state.name(), null);
        if (json == null) return appearance;
        try {
            JSONObject o = new JSONObject(json);
            appearance.customBitmap = o.optBoolean("c", false);
            appearance.bitmapPath = o.has("p") ? o.optString("p", null) : null;
            appearance.scalePercent = clamp(o.optInt("sc", 100), 25, 300);
            appearance.rotationDeg = clamp(o.optInt("ro", 0), -180, 180);
            appearance.opacityPercent = clamp(o.optInt("op", 100), 0, 100);
            appearance.tintColor = o.optInt("ti", 0);
            appearance.glowColor = o.optInt("gc", 0);
            appearance.glowRadius = clamp(o.optInt("gr", 0), 0, 24);
            appearance.borderColor = o.optInt("bc", 0);
            appearance.borderWidth = clamp(o.optInt("bw", 0), 0, 6);
            appearance.shadowRadius = clamp(o.optInt("sr", 0), 0, 12);
            appearance.hotspotX = o.optInt("hx", -1);
            appearance.hotspotY = o.optInt("hy", -1);
        } catch (JSONException ignored) {
            // Corrupt entry — fall back to the pristine defaults rather than crashing.
            appearance = new CursorAppearance();
        }
        return appearance;
    }

    public void save(@NonNull CursorState state) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("c", customBitmap);
            if (bitmapPath != null) o.put("p", bitmapPath);
            o.put("sc", scalePercent);
            o.put("ro", rotationDeg);
            o.put("op", opacityPercent);
            o.put("ti", tintColor);
            o.put("gc", glowColor);
            o.put("gr", glowRadius);
            o.put("bc", borderColor);
            o.put("bw", borderWidth);
            o.put("sr", shadowRadius);
            o.put("hx", hotspotX);
            o.put("hy", hotspotY);
        } catch (JSONException ignored) {}
        prefs.edit().putString(PREFIX + state.name(), o.toString()).apply();
    }

    public void reset(@NonNull CursorState state) {
        customBitmap = false;
        bitmapPath = null;
        scalePercent = 100;
        rotationDeg = 0;
        opacityPercent = 100;
        tintColor = 0;
        glowColor = 0;
        glowRadius = 0;
        borderColor = 0;
        borderWidth = 0;
        shadowRadius = 0;
        hotspotX = -1;
        hotspotY = -1;
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs != null) prefs.edit().remove(PREFIX + state.name()).apply();
    }

    public static void resetAll(@NonNull Context context) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (CursorState state : CursorState.values()) {
            editor.remove(PREFIX + state.name());
        }
        editor.apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
