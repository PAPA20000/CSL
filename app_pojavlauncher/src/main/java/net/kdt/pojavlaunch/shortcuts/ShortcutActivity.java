package net.kdt.pojavlaunch.shortcuts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

/**
 * Transparent activity launched by a home screen profile shortcut.
 *
 * Receives the profile UUID from the shortcut intent, sets it as the
 * current profile in preferences, and opens the main launcher activity
 * which will auto-launch the game.
 */
public class ShortcutActivity extends Activity {

    public static final String EXTRA_PROFILE_KEY = "cs_shortcut_profile_key";
    public static final String EXTRA_AUTO_LAUNCH = "cs_shortcut_auto_launch";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            launchLauncher();
            return;
        }

        String profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY);
        if (profileKey == null || profileKey.isEmpty()) {
            launchLauncher();
            return;
        }

        // Ensure profiles are loaded
        LauncherProfiles.loadAsync(() -> {
            MinecraftProfile profile = LauncherProfiles.mainProfileJson != null
                    ? LauncherProfiles.mainProfileJson.profiles.get(profileKey)
                    : null;

            if (profile == null) {
                // Profile was deleted — just open the launcher
                launchLauncher();
                return;
            }

            // Set this profile as the current one
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                    .apply();

            // Launch the launcher
            boolean autoLaunch = intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, true);
            launchLauncher();

            // Auto-launch the game when shortcut is used directly (not editing)
            // The launcher will read the current profile and start the game
            if (autoLaunch) {
                // Signal game launch via ExtraCore after a brief delay
                // to let the launcher activity initialize
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> {
                            net.kdt.pojavlaunch.extra.ExtraCore.setValue(
                                    net.kdt.pojavlaunch.extra.ExtraConstants.LAUNCH_GAME,
                                    true);
                        }, 500);
            }
        });
    }

    private void launchLauncher() {
        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(launcherIntent);
        finish();
    }
}
