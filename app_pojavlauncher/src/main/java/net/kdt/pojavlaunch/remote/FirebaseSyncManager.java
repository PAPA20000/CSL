package net.kdt.pojavlaunch.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.CsPopup;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CS Launcher V3 — Firebase real-time sync (admin-panel driven).
 *
 * Uses Firebase Realtime Database REST + Server-Sent-Events (SSE) so NO
 * Firebase SDK / google-services.json is needed — build stays clean.
 *
 *   • announcements  → /announcements/{id}   (popup / card / page, markdown)
 *   • notifications  → /notifications/{id}   (mini popups, expiry support)
 *   • sponsorship    → /settings/sponsorshipEnabled (global on/off)
 *   • update         → /update               (version check + force update)
 *
 * Real-time: one background thread holds an SSE stream per root key and
 * reconnects automatically; every change from the HTML admin panel shows up
 * in the launcher within ~1-2s. Data is also cached in SharedPreferences so
 * offline launches still show the last known state.
 *
 * Disabled by default — the launcher behaves exactly as before until the
 * user turns on "Firebase Sync" in Settings → Advanced and pastes the DB URL.
 */
public final class FirebaseSyncManager {

    private static final String TAG = "FirebaseSync";
    private static final String PREF = "firebase_sync";
    private static final String PREF_ENABLED = "firebase_sync_enabled";
    private static final String PREF_URL = "firebase_db_url";
    private static final String PREF_SEEN_ANN = "seen_announcements";
    private static final String PREF_SEEN_NTF = "seen_notifications";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sStarted = new AtomicBoolean(false);
    private static volatile boolean sRunning = false;

    private static volatile JSONObject sAnnouncements = new JSONObject();
    private static volatile JSONObject sNotifications = new JSONObject();
    private static volatile JSONObject sSettings = new JSONObject();
    private static volatile JSONObject sUpdate = new JSONObject();

    private static volatile String sDbUrl = "";
    private static volatile String sSeenAnn = "";
    private static volatile String sSeenNtf = "";

    private FirebaseSyncManager() { }

    // ─────────────────────────── lifecycle ───────────────────────────

    /** Call from the launcher's onResume. Idempotent + cheap. */
    public static void onResume(Context ctx) {
        loadCache(ctx);
        if (!isConfigured(ctx)) return;
        start(ctx);
        // UI side-effects run on the main thread.
        UI.post(() -> {
            Activity act = ctx instanceof Activity ? (Activity) ctx : null;
            if (act == null || act.isFinishing()) return;
            checkForUpdate(act);
            showAnnouncements(act);
            showNotifications(act);
            applySponsorshipGates(act);
        });
    }

    public static boolean isConfigured(Context ctx) {
        SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                ? LauncherPreferences.DEFAULT_PREF
                : ctx.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        return p.getBoolean(PREF_ENABLED, false)
                && !p.getString(PREF_URL, "").trim().isEmpty();
    }

    private static void start(Context ctx) {
        if (sStarted.getAndSet(true)) return;
        final String db = dbUrl(ctx);
        sDbUrl = db;
        sRunning = true;
        Thread t = new Thread(() -> streamLoop(db, "/announcements", json -> {
            sAnnouncements = json; persistCache(ctx);
        }), "fb-ann");
        t.setDaemon(true); t.start();
        Thread t2 = new Thread(() -> streamLoop(db, "/notifications", json -> {
            sNotifications = json; persistCache(ctx);
        }), "fb-ntf");
        t2.setDaemon(true); t2.start();
        Thread t3 = new Thread(() -> streamLoop(db, "/settings", json -> {
            sSettings = json; persistCache(ctx);
        }), "fb-set");
        t3.setDaemon(true); t3.start();
        Thread t4 = new Thread(() -> streamLoop(db, "/update", json -> {
            sUpdate = json; persistCache(ctx);
        }), "fb-upd");
        t4.setDaemon(true); t4.start();
    }

    private static String dbUrl(Context ctx) {
        SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                ? LauncherPreferences.DEFAULT_PREF
                : ctx.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        return p.getString(PREF_URL, "").trim().replaceAll("/$", "");
    }

    /** Blocks forever (daemon): SSE stream with auto-reconnect + backoff. */
    private static void streamLoop(String db, String path, java.util.function.Consumer<JSONObject> onData) {
        long backoff = 2000;
        while (sRunning) {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(db + path + ".json").openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0); // stream stays open
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); sleep(backoff = Math.min(backoff * 2, 30000)); continue; }
                backoff = 2000;
                InputStream in = conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                String line;
                while (sRunning && (line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "null".equals(data)) continue;
                    try {
                        JSONObject obj = new JSONObject(data);
                        UI.post(() -> onData.accept(obj));
                    } catch (Exception e) { Log.w(TAG, "parse", e); }
                }
                r.close(); conn.disconnect();
            } catch (Throwable t) {
                Log.w(TAG, "stream " + path + " dropped: " + t.getMessage());
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 30000);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ─────────────────────────── cache ───────────────────────────

    private static void loadCache(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sAnnouncements = new JSONObject(p.getString("cache_ann", "{}"));
            sNotifications = new JSONObject(p.getString("cache_ntf", "{}"));
            sSettings = new JSONObject(p.getString("cache_set", "{}"));
            sUpdate = new JSONObject(p.getString("cache_upd", "{}"));
            sSeenAnn = p.getString(PREF_SEEN_ANN, "");
            sSeenNtf = p.getString(PREF_SEEN_NTF, "");
        } catch (Throwable ignored) {}
    }

    private static void persistCache(Context ctx) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString("cache_ann", sAnnouncements.toString())
                    .putString("cache_ntf", sNotifications.toString())
                    .putString("cache_set", sSettings.toString())
                    .putString("cache_upd", sUpdate.toString())
                    .putString(PREF_SEEN_ANN, sSeenAnn)
                    .putString(PREF_SEEN_NTF, sSeenNtf)
                    .apply();
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────── accessors ───────────────────────────

    public static boolean isSponsorshipEnabled() {
        return sSettings.optBoolean("sponsorshipEnabled", true);
    }

    /** True when a newer published version exists than the running build. */
    public static boolean hasUpdate() {
        String v = sUpdate.optString("version", "");
        return !v.isEmpty() && !v.equalsIgnoreCase(net.kdt.pojavlaunch.BuildConfig.VERSION_NAME);
    }

    public static boolean isForceUpdate() { return sUpdate.optBoolean("force", false); }

    // ─────────────────────────── UI: update ───────────────────────────

    public static void checkForUpdate(Activity act) {
        if (!hasUpdate()) return;
        String version = sUpdate.optString("version", "?");
        String url = sUpdate.optString("url", "");
        String changelog = sUpdate.optString("changelog", "");
        boolean force = isForceUpdate();

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle("Update available — v" + version)
                .setMessage(force
                        ? "A new version is REQUIRED to continue playing.\n\n" + changelog
                        : "A new version is available.\n\n" + changelog)
                .setPositiveButton("Download", (d, w) -> {
                    if (!url.isEmpty()) {
                        try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                        catch (Throwable t) { Log.w(TAG, "open url", t); }
                    }
                    if (!force) d.dismiss();
                });
        if (!force) b.setNegativeButton("Later", null);
        AlertDialog d = b.create();
        d.setCancelable(!force); // force update → block launcher usage
        d.setCanceledOnTouchOutside(false);
        if (force) d.setMessage("A new version is REQUIRED to continue playing.\n\n" + changelog);
        d.show();
    }

    // ─────────────────────────── UI: announcements ───────────────────────────

    public static void showAnnouncements(Activity act) {
        Iterator<String> keys = sAnnouncements.keys();
        List<JSONObject> list = new ArrayList<>();
        while (keys.hasNext()) {
            try {
                JSONObject a = sAnnouncements.optJSONObject(keys.next());
                if (a == null || a.optBoolean("enabled", true) == false) continue;
                list.add(a);
            } catch (Throwable ignored) {}
        }
        list.sort((a, b) -> Boolean.compare(b.optBoolean("pinned"), a.optBoolean("pinned")));
        for (JSONObject a : list) {
            String id = a.optString("id", "");
            if (id.isEmpty() || sSeenAnn.contains(id + ";")) continue;
            sSeenAnn += id + ";";
            persistCache(act.getApplicationContext());
            String title = a.optString("title", "Announcement");
            String body = a.optString("body", "");
            showMarkdownDialog(act, title, body);
        }
    }

    // ─────────────────────────── UI: notifications ───────────────────────────

    public static void showNotifications(Activity act) {
        Iterator<String> keys = sNotifications.keys();
        List<JSONObject> list = new ArrayList<>();
        while (keys.hasNext()) {
            try {
                JSONObject n = sNotifications.optJSONObject(keys.next());
                if (n == null || !n.optBoolean("enabled", true)) continue;
                long exp = n.optLong("expiresAt", 0);
                if (exp > 0 && exp < System.currentTimeMillis()) continue; // expired
                list.add(n);
            } catch (Throwable ignored) {}
        }
        list.sort((a, b) -> Long.compare(b.optLong("createdAt", 0), a.optLong("createdAt", 0)));
        for (JSONObject n : list) {
            String id = n.optString("id", "");
            if (id.isEmpty() || sSeenNtf.contains(id + ";")) continue;
            sSeenNtf += id + ";";
            persistCache(act.getApplicationContext());
            String icon = n.optString("icon", "🔔");
            String title = n.optString("title", "");
            String msg = n.optString("message", "");
            String priority = n.optString("priority", "normal");
            CsPopup.show(act, icon + " " + title + (msg.isEmpty() ? "" : "\n" + msg));
        }
    }

    // ─────────────────────────── UI: sponsorship gates ───────────────────────────

    /** Hides every sponsor view when sponsorship is globally disabled. */
    public static void applySponsorshipGates(Activity act) {
        boolean on = isSponsorshipEnabled();
        int[] ids = {
                R.id.infrawire_home_card,
                R.id.infrawire_card_play,
                R.id.infrawire_card_feed,
                R.id.infrawire_powered_badge
        };
        for (int id : ids) {
            View v = act.findViewById(id);
            if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    /** Fragment-level gate: returns true when sponsor UI may stay visible. */
    public static boolean gateSponsorView(View v) {
        if (v == null) return true;
        boolean on = isSponsorshipEnabled();
        v.setVisibility(on ? View.VISIBLE : View.GONE);
        return on;
    }

    // ─────────────────────────── markdown dialog ───────────────────────────

    private static void showMarkdownDialog(Activity act, String title, String markdown) {
        TextView tv = new TextView(act);
        tv.setPadding(dp(act, 22), dp(act, 10), dp(act, 22), dp(act, 10));
        tv.setTextSize(13.5f);
        tv.setTextColor(0xFFE4E4EA);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setText(Markdown.render(act, markdown));
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
