package net.kdt.pojavlaunch.launch;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * LaunchTracker — the launch-phase state machine.
 *
 * Downloads and launches used to be the SAME signal: ProgressKeeper task
 * records. That is why pressing PLAY on an installed profile felt like
 * "another download": verification opens the very same DOWNLOAD_MINECRAFT
 * record that real installs use, so the Download Console popped up for pure
 * launches.
 *
 * ProgressKeeper stays the UNTouched owner of real download progress
 * (the download animation is perfect and must not change). LaunchTracker adds
 * a separate semantic channel for the LAUNCH sequence:
 *
 *   PREPARING → VERIFYING → RUNTIME (only when a JRE must be installed)
 *             → DOWNLOADING (only when real bytes start flowing)
 *             → STARTING → (game process takes over)
 *   failure   → FAILED → IDLE (auto-clears shortly after)
 *
 * Ordering guarantees: all listener notifications happen on the main thread;
 * phases only move forward while a launch is ACTIVE, so stale worker threads
 * can never resurrect an old sequence.
 */
public final class LaunchTracker {

    public enum Phase { IDLE, PREPARING, VERIFYING, RUNTIME, DOWNLOADING, STARTING, FAILED }

    public interface PhaseListener { void onLaunchPhase(@NonNull Phase phase); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<PhaseListener> sListeners = new ArrayList<>();
    private static volatile Phase sPhase = Phase.IDLE;
    private static volatile String sProfileName;
    private static volatile String sVersionId;

    private LaunchTracker() {}

    /** Begin a fresh launch sequence (called right before MinecraftDownloader.start). */
    public static synchronized void begin(@Nullable String profileName, @Nullable String versionId) {
        sProfileName = profileName;
        sVersionId = versionId;
        setPhase(Phase.PREPARING);
    }

    /** Game files + natives are being verified. */
    public static void verifying() { advance(Phase.VERIFYING); }
    /** A runtime needs installing — console takes over the chrome. */
    public static void runtime() { advance(Phase.RUNTIME); }
    /** Real download bytes started flowing — console takes over the chrome. */
    public static void downloading() { advance(Phase.DOWNLOADING); }
    /** Everything checked out — the game process is about to start. */
    public static void starting() { advance(Phase.STARTING); }

    /** Launch aborted (error or user cancel). Auto-clears to IDLE shortly. */
    public static void fail() {
        setPhase(Phase.FAILED);
        MAIN.postDelayed(() -> {
            synchronized (LaunchTracker.class) {
                if (sPhase == Phase.FAILED) setPhase(Phase.IDLE);
            }
        }, 600);
    }

    /** Hard reset (e.g. activity destroyed mid-launch). */
    public static void idle() { setPhase(Phase.IDLE); }

    @NonNull public static Phase getPhase() { return sPhase; }
    @Nullable public static String getProfileName() { return sProfileName; }
    @Nullable public static String getVersionId() { return sVersionId; }

    /**
     * While the launch sequence owns the chrome, the Download Console must
     * stay hidden. Only phases where REAL downloads happen (RUNTIME /
     * DOWNLOADING / IDLE) let the console surface — verification never does.
     */
    public static boolean suppressesDownloadConsole() {
        Phase p = sPhase;
        return p == Phase.PREPARING || p == Phase.VERIFYING || p == Phase.STARTING;
    }

    private static void advance(@NonNull Phase next) {
        synchronized (LaunchTracker.class) {
            if (!isActiveLocked()) return; // never resurrect an idle/dead sequence
            // STARTING is terminal; FAILED is terminal.
            if (sPhase == Phase.STARTING) return;
            if (sPhase == next) return;
            setPhase(next);
        }
    }

    private static boolean isActiveLocked() {
        return sPhase == Phase.PREPARING || sPhase == Phase.VERIFYING
                || sPhase == Phase.RUNTIME || sPhase == Phase.DOWNLOADING;
    }

    private static void setPhase(@NonNull Phase phase) {
        final List<PhaseListener> snapshot;
        synchronized (LaunchTracker.class) {
            if (sPhase == phase) return;
            sPhase = phase;
            snapshot = new ArrayList<>(sListeners);
        }
        MAIN.post(() -> {
            for (int i = 0; i < snapshot.size(); i++) snapshot.get(i).onLaunchPhase(phase);
        });
    }

    public static void addListener(@NonNull PhaseListener listener) {
        synchronized (LaunchTracker.class) {
            if (!sListeners.contains(listener)) sListeners.add(listener);
        }
    }

    public static void removeListener(@NonNull PhaseListener listener) {
        synchronized (LaunchTracker.class) {
            sListeners.remove(listener);
        }
    }
}
