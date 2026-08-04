package net.kdt.pojavlaunch.services;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * ProcessWarmService — a featherweight no-op service living in the ":game"
 * process. The launcher starts it the moment the user presses Play so that
 * the game process is already warm (zygote fork + VM up) by the time the
 * launch sequence finishes. MainActivity then materialises its window almost
 * instantly instead of paying the cold-process 1–2s dead gap.
 *
 * It does nothing else — no wake locks, no threads, zero steady-state cost —
 * and dies with the game process.
 */
public class ProcessWarmService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Presence is the entire point; the process being alive is the work.
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
