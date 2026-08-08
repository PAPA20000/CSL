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
import android.view.ViewGroup;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.CsPopup;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CS Launcher V3 — Firebase real-time sync (admin panel driven).
 *
 * Uses the official Firebase SDK (google-services.json):
 *   • announcements  → /announcements/{id}   (popup / card / page, markdown)
 *   • notifications  → /notifications/{id}   (mini popups, expiry support)
 *   • sponsorship    → /settings/sponsorshipEnabled (global on/off)
 *   • update         → /update               (version check + force update)
 *
 * Real-time: ValueEventListener per root key — every change from the HTML
 * admin panel appears in the launcher within ~1s, no restart needed. The SDK
 * keeps an offline cache, so the last known state is shown without internet.
 *
 * Auto-enabled when google-services.json provides a database URL (default).
 * The Advanced settings toggle can disable it, and the Database URL field
 * can override the default.
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

    private static volatile JSONObject sAnnouncements = new JSONObject();
    private static volatile JSONObject sNotifications = new JSONObject();
    private static volatile JSONObject sSettings = new JSONObject();
    private static volatile JSONObject sUpdate = new JSONObject();

    private static volatile String sSeenAnn = "";
    private static volatile String sSeenNtf = "";

    private static final java.util.Set<String> sDismissedBanners = new java.util.concurrent.CopyOnWriteArraySet<>();
    private static volatile Runnable sHomeBannerListener;

    private FirebaseSyncManager() { }

    // ─────────────────────────── lifecycle ───────────────────────────

    /** Call from the launcher's onResume. Idempotent + cheap. */
    public static void onResume(Context ctx) {
        loadCache(ctx);
        if (!isConfigured(ctx)) return;
        start(ctx);
        UI.post(() -> {
            Activity act = ctx instanceof Activity ? (Activity) ctx : null;
            if (act == null || act.isFinishing()) return;
            checkForUpdate(act);
            showAnnouncements(act);
            showNotifications(act);
            applySponsorshipGates(act);
        });
    }

    /** The database URL: settings override, else google-services.json value. */
    public static String effectiveDbUrl(Context ctx) {
        String custom = dbUrlFromPrefs(ctx);
        if (!custom.isEmpty()) return custom;
        try {
            String res = ctx.getString(R.string.firebase_database_url);
            if (res != null && res.startsWith("https://")) return res;
        } catch (Throwable ignored) {}
        return "https://cs-launcher-v3-default-rtdb.asia-southeast1.firebasedatabase.app";
    }

    public static boolean isConfigured(Context ctx) {
        String url = effectiveDbUrl(ctx);
        if (url == null || url.isEmpty()) return false;
        return prefs(ctx).getBoolean(PREF_ENABLED, true);
    }

    private static SharedPreferences prefs(Context ctx) {
        return LauncherPreferences.DEFAULT_PREF != null
                ? LauncherPreferences.DEFAULT_PREF
                : ctx.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
    }

    private static String dbUrlFromPrefs(Context ctx) {
        return prefs(ctx).getString(PREF_URL, "").trim().replaceAll("/$", "");
    }

    private static void start(Context ctx) {
        if (sStarted.getAndSet(true)) return;
        final String db = effectiveDbUrl(ctx);
        if (db.isEmpty()) return;
        try {
            CsFirebaseMessagingService.initFcm(ctx);
            FirebaseDatabase dbInst = FirebaseDatabase.getInstance(db);
            try { dbInst.setPersistenceEnabled(true); } catch (Throwable ignored) {}
            attach(dbInst, "/announcements", json -> { sAnnouncements = json; persistCache(ctx); notifyHomeBanner(); });
            attach(dbInst, "/notifications", json -> { sNotifications = json; persistCache(ctx); notifyHomeBanner(); });
            attach(dbInst, "/settings", json -> { sSettings = json; persistCache(ctx); });
            attach(dbInst, "/update", json -> { sUpdate = json; persistCache(ctx); });
        } catch (Throwable t) {
            Log.w(TAG, "init failed", t);
        }
    }

    private static JSONObject dataSnapshotToJson(DataSnapshot snapshot) {
        JSONObject obj = new JSONObject();
        if (snapshot == null || !snapshot.exists()) return obj;
        for (DataSnapshot child : snapshot.getChildren()) {
            try {
                String k = child.getKey();
                if (k == null) continue;
                if (child.hasChildren()) {
                    JSONObject nested = new JSONObject();
                    for (DataSnapshot prop : child.getChildren()) {
                        String pk = prop.getKey();
                        Object pv = prop.getValue();
                        if (pk != null && pv != null) nested.put(pk, pv);
                    }
                    if (!nested.has("id")) nested.put("id", k);
                    obj.put(k, nested);
                } else {
                    Object v = child.getValue();
                    if (v != null) obj.put(k, v);
                }
            } catch (Throwable ignored) {}
        }
        return obj;
    }

    private static void attach(FirebaseDatabase db, String path,
                               java.util.function.Consumer<JSONObject> onData) {
        DatabaseReference ref = db.getReference(path);
        ref.addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                try {
                    JSONObject obj = dataSnapshotToJson(snapshot);
                    UI.post(() -> onData.accept(obj));
                } catch (Throwable e) {
                    Log.w(TAG, "parse " + path, e);
                }
            }
            @Override public void onCancelled(DatabaseError error) {
                Log.w(TAG, "cancelled " + path + ": " + error.getMessage());
            }
        });
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

    public static void checkForUpdateFromFcm(Activity act, String version, String url, String changelog) {
        if (version != null && !version.trim().isEmpty() && url != null && !url.trim().isEmpty()) {
            try {
                sUpdate.put("version", version);
                sUpdate.put("url", url);
                sUpdate.put("changelog", changelog != null ? changelog : "");
            } catch (Throwable ignored) {}
        }
        if (hasUpdate()) {
            checkForUpdate(act);
        } else {
            checkForUpdateManual(act);
        }
    }

    public static void checkForUpdate(Activity act) {
        if (!hasUpdate()) return;
        String version = sUpdate.optString("version", "?");
        String url = sUpdate.optString("url", "");
        String changelog = sUpdate.optString("changelog", "");
        boolean force = isForceUpdate();

        try {
            final android.app.Dialog dialog = new android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            }
            dialog.setContentView(R.layout.dialog_launcher_update);
            dialog.setCancelable(!force);
            dialog.setCanceledOnTouchOutside(false);

            TextView tvTitle = dialog.findViewById(R.id.tv_update_title);
            TextView tvVersion = dialog.findViewById(R.id.tv_update_version);
            TextView tvChangelog = dialog.findViewById(R.id.tv_update_changelog);
            View progressContainer = dialog.findViewById(R.id.update_progress_container);
            TextView tvProgressText = dialog.findViewById(R.id.tv_update_progress_text);
            ProgressBar progressBar = dialog.findViewById(R.id.update_progress_bar);
            TextView btnLater = dialog.findViewById(R.id.btn_update_later);
            TextView btnUpdate = dialog.findViewById(R.id.btn_update_now);

            if (tvTitle != null) tvTitle.setText("UPDATE");
            if (tvVersion != null) tvVersion.setText("New version available — v" + version);
            if (tvChangelog != null) {
                tvChangelog.setText((force
                        ? "A new version is REQUIRED to continue playing.\n\n"
                        : "") + (changelog.isEmpty() ? "New features, performance enhancements, and bug fixes." : changelog));
            }

            if (btnLater != null) {
                if (force) {
                    btnLater.setVisibility(View.GONE);
                } else {
                    btnLater.setOnClickListener(v -> dialog.dismiss());
                }
            }

            if (btnUpdate != null) {
                btnUpdate.setOnClickListener(v -> {
                    if (url.isEmpty()) {
                        tvProgressText.setText("Error: Download URL is missing.");
                        if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
                        return;
                    }
                    downloadUpdateApkInLauncher(act, url, version, dialog, progressContainer, tvProgressText, progressBar, btnLater, btnUpdate);
                });
            }
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "checkForUpdate dialog failed", t);
        }
    }

    private static void downloadUpdateApkInLauncher(Activity act, String urlStr, String version,
                                                    android.app.Dialog dialog, View progressContainer,
                                                    TextView tvProgressText, ProgressBar progressBar,
                                                    TextView btnLater, TextView btnUpdate) {
        if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
        if (btnLater != null) btnLater.setVisibility(View.GONE);
        if (btnUpdate != null) {
            btnUpdate.setEnabled(false);
            btnUpdate.setText("DOWNLOADING...");
        }
        if (tvProgressText != null) tvProgressText.setText("Downloading update... 0%");
        if (progressBar != null) progressBar.setProgress(0);

        new Thread(() -> {
            File apkFile = null;
            try {
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || !dir.exists()) dir = act.getCacheDir();
                apkFile = new File(dir, "CS_LAUNCHER_V3_" + version + ".apk");
                if (apkFile.exists()) apkFile.delete();

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "CSLauncher-Updater/" + version);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException("Server returned HTTP " + responseCode);
                }

                int totalLength = conn.getContentLength();
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(apkFile);

                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;
                int lastPercent = -1;

                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    totalRead += len;
                    if (totalLength > 0) {
                        int percent = (int) ((totalRead * 100) / totalLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int p = percent;
                            act.runOnUiThread(() -> {
                                if (progressBar != null) progressBar.setProgress(p);
                                if (tvProgressText != null) tvProgressText.setText("Downloading update... " + p + "%");
                            });
                        }
                    }
                }
                fos.flush();
                fos.close();
                is.close();

                final File finalApk = apkFile;
                act.runOnUiThread(() -> {
                    if (tvProgressText != null) tvProgressText.setText("Update downloaded");
                    if (progressBar != null) progressBar.setProgress(100);
                    if (btnUpdate != null) {
                        btnUpdate.setText("INSTALL NOW");
                        btnUpdate.setEnabled(true);
                        btnUpdate.setOnClickListener(v -> installApk(act, finalApk));
                    }
                    installApk(act, finalApk);
                });

            } catch (Throwable t) {
                Log.e(TAG, "Update download failed", t);
                final String err = t.getMessage() != null ? t.getMessage() : "Download error";
                act.runOnUiThread(() -> {
                    if (tvProgressText != null) tvProgressText.setText("Download failed: " + err);
                    if (btnUpdate != null) {
                        btnUpdate.setText("RETRY");
                        btnUpdate.setEnabled(true);
                        btnUpdate.setOnClickListener(v -> downloadUpdateApkInLauncher(act, urlStr, version, dialog, progressContainer, tvProgressText, progressBar, btnLater, btnUpdate));
                    }
                    if (btnLater != null && !isForceUpdate()) btnLater.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private static void installApk(Activity act, File apkFile) {
        try {
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(act,
                        act.getPackageName() + ".provider", apkFile);
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(installIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch package installer", t);
            if (act != null) {
                act.runOnUiThread(() -> {
                    try {
                        new AlertDialog.Builder(act)
                                .setTitle("Installation Error")
                                .setMessage("Could not start package installer: " + t.getMessage())
                                .setPositiveButton("OK", null)
                                .show();
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    // ─────────────────────────── UI: announcements ───────────────────────────

    public static void showAnnouncements(Activity act) {
        Iterator<String> keys = sAnnouncements.keys();
        List<JSONObject> list = new ArrayList<>();
        while (keys.hasNext()) {
            try {
                String k = keys.next();
                JSONObject a = sAnnouncements.optJSONObject(k);
                if (a == null || !a.optBoolean("enabled", true)) continue;
                if (!a.has("id")) a.put("id", k);
                list.add(a);
            } catch (Throwable ignored) {}
        }
        list.sort((a, b) -> Boolean.compare(b.optBoolean("pinned"), a.optBoolean("pinned")));
        for (JSONObject a : list) {
            String id = a.optString("id", "ann_" + a.optLong("createdAt", 0));
            String seenKey = id + "_" + a.optLong("updatedAt", a.optLong("createdAt", 0));
            if (id.isEmpty() || (!a.optBoolean("force_show", false) && sSeenAnn.contains(seenKey + ";"))) continue;
            sSeenAnn += seenKey + ";";
            persistCache(act.getApplicationContext());
            String typeStr = a.optString("announcement_type", a.optString("type", "POPUP"));
            boolean fullPage = "FULL_SCREEN".equalsIgnoreCase(typeStr) || "page".equalsIgnoreCase(typeStr);
            showMarkdownDialog(act, a.optString("title", "Announcement"),
                    a.optString("body", ""), fullPage);
        }
    }

    // ─────────────────────────── UI: notifications (removed per user directive) ───────────────────────────

    public static void showNotifications(Activity act) {
        // No-op: user requested removing noisy notifications; announcements handle all communication.
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

    public static void showMarkdownDialog(Activity act, String title, String markdown) {
        showMarkdownDialog(act, title, markdown, false);
    }

    public static void showMarkdownDialog(Activity act, String title, String markdown, boolean fullPage) {
        try {
            final android.app.Dialog dialog = new android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(fullPage ? 0xFF121212 : 0));
            }
            dialog.setContentView(fullPage ? R.layout.page_announcement_fullscreen : R.layout.page_announcement_popup);

            TextView tvTitle = dialog.findViewById(R.id.tv_ann_page_title);
            TextView tvBody = dialog.findViewById(R.id.tv_ann_page_body);
            View btnClose = dialog.findViewById(R.id.btn_ann_page_close);

            if (tvTitle != null) tvTitle.setText(title);
            if (tvBody != null) {
                tvBody.setMovementMethod(LinkMovementMethod.getInstance());
                tvBody.setText(Markdown.render(act, markdown));
            }
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "showXmlDialog failed", t);
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    // ─────────────────────────── Home Banner (Mini Notifications & Announcements) ───────────────────────────

    public static class HomeBannerItem {
        public final String id;
        public final String icon;
        public final String title;
        public final String body;
        public final boolean isAnnouncement;

        public HomeBannerItem(String id, String icon, String title, String body, boolean isAnnouncement) {
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.body = body;
            this.isAnnouncement = isAnnouncement;
        }
    }

    public static void setHomeBannerListener(Runnable listener) {
        sHomeBannerListener = listener;
        notifyHomeBanner();
    }

    private static void notifyHomeBanner() {
        UI.post(() -> {
            if (sHomeBannerListener != null) sHomeBannerListener.run();
        });
    }

    public static HomeBannerItem getLatestHomeBanner() {
        // 1. Check notifications
        Iterator<String> nKeys = sNotifications.keys();
        List<JSONObject> nList = new ArrayList<>();
        while (nKeys.hasNext()) {
            try {
                String k = nKeys.next();
                JSONObject n = sNotifications.optJSONObject(k);
                if (n == null || !n.optBoolean("enabled", true)) continue;
                if (!n.has("id")) n.put("id", k);
                String id = n.optString("id", k);
                if (id.isEmpty() || sDismissedBanners.contains(id)) continue;
                long exp = n.optLong("expiresAt", 0);
                if (exp > 0 && exp < System.currentTimeMillis()) continue;
                nList.add(n);
            } catch (Throwable ignored) {}
        }
        nList.sort((a, b) -> Long.compare(b.optLong("createdAt", 0), a.optLong("createdAt", 0)));
        if (!nList.isEmpty()) {
            JSONObject n = nList.get(0);
            return new HomeBannerItem(
                    n.optString("id", "ntf"),
                    n.optString("icon", ""),
                    n.optString("title", ""),
                    n.optString("message", ""),
                    false
            );
        }

        // 2. Check announcements
        Iterator<String> aKeys = sAnnouncements.keys();
        List<JSONObject> aList = new ArrayList<>();
        while (aKeys.hasNext()) {
            try {
                String k = aKeys.next();
                JSONObject a = sAnnouncements.optJSONObject(k);
                if (a == null || !a.optBoolean("enabled", true)) continue;
                if (!a.has("id")) a.put("id", k);
                String id = a.optString("id", k);
                if (id.isEmpty() || sDismissedBanners.contains(id)) continue;
                aList.add(a);
            } catch (Throwable ignored) {}
        }
        aList.sort((a, b) -> Boolean.compare(b.optBoolean("pinned"), a.optBoolean("pinned")));
        if (!aList.isEmpty()) {
            JSONObject a = aList.get(0);
            return new HomeBannerItem(
                    a.optString("id", "ann"),
                    "",
                    a.optString("title", "Announcement"),
                    a.optString("body", ""),
                    true
            );
        }

        return null;
    }

    public static void dismissBanner(String id, Context ctx) {
        if (id != null && !id.isEmpty()) {
            sDismissedBanners.add(id);
            notifyHomeBanner();
        }
    }

    // ─────────────────────────── Manual Update Check (Settings) ───────────────────────────

    public static void checkForUpdateManual(Activity act) {
        if (hasUpdate()) {
            checkForUpdate(act);
        } else {
            CsPopup.show(act, "✅ CS Launcher V3 is up to date!");
        }
    }
}
