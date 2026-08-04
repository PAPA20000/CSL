package net.kdt.pojavlaunch.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.utils.DownloadControl;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ProgressService — the download notification center of CS Launcher.
 *
 * Keeps its original purpose (foreground process anchor while tasks run)
 * but now also renders one live grouped notification PER download record:
 * name, real-time percentage, size detail, speed, ETA, plus Pause / Resume /
 * Stop / Open-launcher actions wired straight into {@link DownloadControl},
 * which the monitored copy loops already honor. When a record ends, the child
 * notification flips to a short-lived "Download complete" card; tapping it
 * (or any child) opens the launcher.
 *
 * The legacy aggregate "tasks in progress" card stays as the GROUP SUMMARY
 * so existing behavior (including its kill action) is preserved.
 */
public class ProgressService extends Service implements TaskCountListener {

    private static final String TAG = "ProgressService";
    private static final String GROUP_DOWNLOADS = "cs_downloads";
    private static final int CHILD_ID_BASE = 1000;
    private static final int COMPLETE_ID_BASE = 9000;
    private static final long MIN_NOTIFY_INTERVAL_MS = 600;
    private static final long COMPLETE_TIMEOUT_MS = 5000;

    public static final String ACTION_TOGGLE_PAUSE = "net.kdt.pojavlaunch.NOTIF_TOGGLE_PAUSE";
    public static final String ACTION_STOP = "net.kdt.pojavlaunch.NOTIF_STOP";
    public static final String EXTRA_RECORD = "record";

    private NotificationManagerCompat mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;

    private final Map<String, RecordListener> mRecordListeners = new HashMap<>();

    /** Simple wrapper to start the service */
    public static void startService(Context context){
        Intent intent = new Intent(context, ProgressService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        mNotificationManager = NotificationManagerCompat.from(getApplicationContext());
        Intent killIntent = new Intent(getApplicationContext(), ProgressService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_PROGRESS_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mNotificationBuilder = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setGroup(GROUP_DOWNLOADS)
                .setGroupSummary(true)
                .setContentIntent(openLauncherIntent(null))
                .setNotificationSilent();
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null) {
            if(intent.getBooleanExtra("kill", false)) {
                stopSelf(); // otherwise Android tries to restart the service since it "crashed"
                Process.killProcess(Process.myPid());
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE_PAUSE.equals(intent.getAction())) {
                String record = intent.getStringExtra(EXTRA_RECORD);
                if (record != null) {
                    DownloadControl.requestPause(record, !DownloadControl.isPaused(record));
                    refreshRecordNotification(record, true);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                String record = intent.getStringExtra(EXTRA_RECORD);
                if (record != null) {
                    // clear pause so the monitored loop can observe the cancel immediately
                    DownloadControl.requestPause(record, false);
                    DownloadControl.requestCancel(record);
                    cancelChild(record);
                }
            }
        }
        Log.d(TAG, "Started!");
        mNotificationBuilder.setContentText(getString(R.string.progresslayout_tasks_in_progress, ProgressKeeper.getTaskCount()));
        Notification notification = mNotificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification);
        }
        if(ProgressKeeper.getTaskCount() < 1) stopSelf();
        else {
            ProgressKeeper.addTaskCountListener(this, false);
            resyncRecords();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ProgressKeeper.removeTaskCountListener(this);
        for (Map.Entry<String, RecordListener> e : mRecordListeners.entrySet()) {
            ProgressKeeper.removeListener(e.getKey(), e.getValue());
            mNotificationManager.cancel(childId(e.getKey()));
        }
        mRecordListeners.clear();
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        Tools.MAIN_HANDLER.post(()->{
            if(taskCount > 0) {
                mNotificationBuilder.setContentText(getString(R.string.progresslayout_tasks_in_progress, taskCount));
                notifySafely(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, mNotificationBuilder.build());
                resyncRecords();
            }else{
                stopSelf();
            }
        });
    }

    // ------------------------------------------------------------ record wiring

    private void resyncRecords() {
        Set<String> active = ProgressKeeper.getActiveRecords();
        // attach listeners for new records
        for (String record : active) {
            if (!mRecordListeners.containsKey(record)) {
                RecordListener listener = new RecordListener(record);
                mRecordListeners.put(record, listener);
                ProgressKeeper.addListener(record, listener);
                // First paint: whatever the record last reported (may be a quiet no-op start)
                refreshRecordNotification(record, true);
            }
        }
        // records that vanished without an explicit END are treated as finished
        for (String tracked : new ArrayList<>(mRecordListeners.keySet())) {
            if (!active.contains(tracked)) completeRecord(tracked);
        }
    }

    private final class RecordListener implements ProgressListener {
        final String record;
        long lastNotifyMs;
        int lastProgress;
        int lastResid = -1;
        Object[] lastVa;

        RecordListener(String record) { this.record = record; }

        @Override public void onProgressStarted() {
            Tools.MAIN_HANDLER.post(() -> refreshRecordNotification(record, true));
        }

        @Override public void onProgressUpdated(int progress, int resid, Object... va) {
            lastProgress = progress;
            lastResid = resid;
            lastVa = va;
            Tools.MAIN_HANDLER.post(() -> refreshRecordNotification(record, false));
        }

        @Override public void onProgressEnded() {
            Tools.MAIN_HANDLER.post(() -> completeRecord(record));
        }
    }

    /** Render (or throttle-render) the child notification for one record. */
    private void refreshRecordNotification(String record, boolean force) {
        RecordListener rl = mRecordListeners.get(record);
        if (rl == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - rl.lastNotifyMs < MIN_NOTIFY_INTERVAL_MS) return;
        rl.lastNotifyMs = now;
        notifySafely(childId(record), buildChild(record, rl));
    }

    private Notification buildChild(String record, RecordListener rl) {
        boolean paused = DownloadControl.isPaused(record);
        int progress = Math.max(0, Math.min(100, rl.lastProgress));

        ParsedStats stats = parseStats(record, rl.lastResid, rl.lastVa);
        String title = resolveTitle(record, rl.lastResid, rl.lastVa, stats);
        StringBuilder text = new StringBuilder();
        if (stats.detail != null && !stats.detail.isEmpty()) text.append(stats.detail);
        if (stats.speedMbps != null) {
            if (text.length() > 0) text.append("  •  ");
            text.append(String.format(Locale.US, "%.1f MB/s", stats.speedMbps));
        }
        if (stats.eta != null && !stats.eta.isEmpty()) {
            if (text.length() > 0) text.append("  •  ");
            text.append(stats.eta);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(title)
                .setContentText(text.length() > 0 ? text.toString() : getString(R.string.newdl_starting))
                .setSubText(paused ? getString(R.string.cs_notif_paused) : null)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setGroup(GROUP_DOWNLOADS)
                .setContentIntent(openLauncherIntent(record))
                .setNotificationSilent();

        // Pause / Resume
        b.addAction(0,
                getString(paused ? R.string.cs_notif_resume : R.string.cs_notif_pause),
                serviceIntent(ACTION_TOGGLE_PAUSE, record));
        // Stop
        b.addAction(0, getString(R.string.cs_notif_stop), serviceIntent(ACTION_STOP, record));
        // Open launcher
        b.addAction(0, getString(R.string.cs_notif_open), openLauncherIntent(record));
        return b.build();
    }

    private void completeRecord(String record) {
        RecordListener rl = mRecordListeners.remove(record);
        if (rl != null) ProgressKeeper.removeListener(record, rl);
        mNotificationManager.cancel(childId(record));
        // short-lived completion card; tapping opens the launcher
        ParsedStats stats = rl != null ? parseStats(record, rl.lastResid, rl.lastVa) : ParsedStats.EMPTY;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(getString(R.string.cs_notif_complete_title))
                .setContentText(getString(R.string.cs_notif_complete_text,
                        resolveTitle(record, rl != null ? rl.lastResid : -1, rl != null ? rl.lastVa : null, stats)))
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .setTimeoutAfter(COMPLETE_TIMEOUT_MS)
                .setGroup(GROUP_DOWNLOADS)
                .setContentIntent(openLauncherIntent(record))
                .setNotificationSilent();
        notifySafely(completeId(record), b.build());
    }

    private void cancelChild(String record) {
        RecordListener rl = mRecordListeners.remove(record);
        if (rl != null) ProgressKeeper.removeListener(record, rl);
        mNotificationManager.cancel(childId(record));
    }

    // ------------------------------------------------------------ stats parsing

    private static final class ParsedStats {
        static final ParsedStats EMPTY = new ParsedStats();
        String detail;
        Double speedMbps;
        String eta;
        String contentName;
        String contentType;
    }

    /** Mirrors ProgressLayout's payload decoding so notifications show the exact same numbers. */
    private ParsedStats parseStats(String record, int resid, Object[] va) {
        ParsedStats stats = new ParsedStats();
        if (va == null) return stats;
        try {
            if (va.length >= 9) {
                // Rich mod/modpack download payload
                stats.contentName = (String) va[5];
                stats.contentType = (String) va[8];
                double currentMB = ((Number) va[1]).doubleValue();
                double totalMB = ((Number) va[2]).doubleValue();
                double speed = ((Number) va[3]).doubleValue();
                double remainingSec = ((Number) va[4]).doubleValue();
                if (totalMB > 0) stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (speed > 0) stats.speedMbps = speed;
                if (remainingSec >= 0) stats.eta = formatRemainingTime(remainingSec);
            } else if (resid == R.string.newdl_downloading_game_files_size && va.length >= 3) {
                double currentMB = ((Number) va[0]).doubleValue();
                double totalMB = ((Number) va[1]).doubleValue();
                double speed = ((Number) va[2]).doubleValue();
                stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (speed > 0) {
                    stats.speedMbps = speed;
                    double remainingMB = Math.max(0, totalMB - currentMB);
                    stats.eta = formatRemainingTime(remainingMB / speed);
                }
            } else if (resid == R.string.newdl_downloading_game_files && va.length >= 2) {
                long currentFiles = ((Number) va[0]).longValue();
                long totalFiles = ((Number) va[1]).longValue();
                stats.detail = currentFiles + " / " + totalFiles + " files";
                if (va.length >= 3) {
                    double filesPerSec = ((Number) va[2]).doubleValue();
                    if (filesPerSec > 0) {
                        stats.eta = formatRemainingTime(Math.max(0, totalFiles - currentFiles) / filesPerSec);
                    }
                }
            } else if (va.length >= 2 && va[0] instanceof Number && va[1] instanceof Number) {
                double currentMB = ((Number) va[0]).doubleValue();
                double totalMB = ((Number) va[1]).doubleValue();
                if (totalMB > 0) stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (va.length >= 3) {
                    double speed = ((Number) va[2]).doubleValue();
                    if (speed > 0) {
                        stats.speedMbps = speed;
                        double remainingMB = Math.max(0, totalMB - currentMB);
                        stats.eta = formatRemainingTime(remainingMB / speed);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Malformed payloads must degrade to a plain progress bar, never a crash.
        }
        return stats;
    }

    /** Same human-readable title rules as the Download Console. */
    private String resolveTitle(String record, int resid, Object[] va, ParsedStats stats) {
        if (resid == R.string.fabric_dl_progress) {
            return "Downloading Fabric" + (va != null && va.length > 0 && va[0] != null ? " " + va[0] : "");
        } else if (resid == R.string.forge_dl_progress) {
            String loaderName = "Forge";
            String verSuffix = "";
            if (va != null && va.length > 0 && va[0] != null) {
                String verStr = String.valueOf(va[0]);
                if (verStr.toLowerCase().contains("neoforge")) loaderName = "";
                verSuffix = " " + verStr;
            }
            return "Downloading " + loaderName + verSuffix;
        } else if (resid == R.string.of_dl_progress) {
            return "Downloading OptiFine" + (va != null && va.length > 0 && va[0] != null ? " " + va[0] : "");
        } else if (resid == R.string.neoforge_dl_searching) {
            return "Searching NeoForge…";
        } else if (resid == R.string.forge_dl_searching) {
            return "Searching Forge…";
        }
        switch (record) {
            case com.kdt.mcgui.ProgressLayout.DOWNLOAD_MINECRAFT:
                return "Downloading Minecraft";
            case com.kdt.mcgui.ProgressLayout.UNPACK_RUNTIME:
                return "Downloading Java Runtime";
            case com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK:
                if (stats.contentType != null && stats.contentName != null) {
                    String typeStr = stats.contentType.substring(0, 1).toUpperCase(Locale.ROOT) + stats.contentType.substring(1);
                    if ("resourcepack".equals(stats.contentType)) typeStr = "Resource Pack";
                    if ("shader".equals(stats.contentType)) typeStr = "Shader Pack";
                    return "Downloading " + typeStr + ": " + stats.contentName;
                }
                return "Installing Modpack";
            case com.kdt.mcgui.ProgressLayout.EXTRACT_COMPONENTS:
                return "Extracting Components";
            case com.kdt.mcgui.ProgressLayout.EXTRACT_SINGLE_FILES:
                return "Extracting Files";
            case com.kdt.mcgui.ProgressLayout.DOWNLOAD_VERSION_LIST:
                return "Fetching Version List";
            default:
                if (resid > 0) {
                    try {
                        return getString(resid);
                    } catch (Throwable ignored) {}
                }
                if (va != null && va.length > 0 && va[0] instanceof String) return (String) va[0];
                return "Downloading…";
        }
    }

    private static String formatRemainingTime(double seconds) {
        if (seconds < 0) return "";
        long total = (long) seconds;
        long hours = total / 3600, minutes = (total % 3600) / 60, secs = total % 60;
        if (hours > 0) return String.format(Locale.US, "%dh %dm left", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%dm %ds left", minutes, secs);
        return String.format(Locale.US, "%ds left", secs);
    }

    // ------------------------------------------------------------ plumbing

    private PendingIntent serviceIntent(String action, String record) {
        Intent intent = new Intent(this, ProgressService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_RECORD, record);
        return PendingIntent.getService(this, childId(record), intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openLauncherIntent(@Nullable String record) {
        Intent intent = new Intent(this, net.kdt.pojavlaunch.LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (record != null) intent.putExtra("cs_open_downloads", true);
        return PendingIntent.getActivity(this, record != null ? childId(record) : 0, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifySafely(int id, Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            mNotificationManager.notify(id, notification);
        } catch (SecurityException se) {
            Log.w(TAG, "Notification blocked (no permission)", se);
        }
    }

    private static int childId(String record) {
        return CHILD_ID_BASE + (record.hashCode() & 0x3FFF);
    }

    private static int completeId(String record) {
        return COMPLETE_ID_BASE + (record.hashCode() & 0x3FFF);
    }
}
