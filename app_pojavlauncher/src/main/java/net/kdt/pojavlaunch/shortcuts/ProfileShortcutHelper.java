package net.kdt.pojavlaunch.shortcuts;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Manages Android home screen shortcuts for Minecraft profiles.
 *
 * Supports three paths depending on API level:
 * - API 26+: ShortcutManager.requestPinShortcut (preferred)
 * - API 25:  ShortcutManager.requestPinShortcut (deprecated, but still works)
 * - API 21-24: Legacy INSTALL_SHORTCUT broadcast (works on most launchers)
 */
public class ProfileShortcutHelper {

    private static final String TAG = "ProfileShortcutHelper";
    private static final String PREFS_KEY_SHORTCUTS = "pinned_shortcuts";
    private static final String SHORTCUTS_DIR = "shortcut_icons";

    /**
     * Create a home screen shortcut for the given profile.
     *
     * @param context     Application or activity context
     * @param profileKey  The profile's UUID key in LauncherProfiles
     * @param profile     The profile to create a shortcut for
     * @param customName  Optional custom name (falls back to profile.name)
     * @param iconBitmap  Optional icon bitmap (falls back to profile icon)
     */
    public static void createShortcut(@NonNull Context context,
                                       @NonNull String profileKey,
                                       @NonNull MinecraftProfile profile,
                                       @Nullable String customName,
                                       @Nullable Bitmap iconBitmap) {
        String shortcutName = customName != null && !customName.isEmpty()
                ? customName : profile.name;

        if (shortcutName == null || shortcutName.isEmpty()) {
            shortcutName = "Minecraft";
        }

        // Resolve icon
        Bitmap icon = iconBitmap;
        if (icon == null) {
            icon = resolveProfileIcon(context, profileKey, profile);
        }
        if (icon == null) {
            icon = drawableToBitmap(
                    context.getDrawable(android.R.drawable.ic_menu_manage));
        }
        if (icon == null) return;

        // Save icon to internal storage for persistence
        saveShortcutIcon(context, profileKey, icon);

        // Create the shortcut ID
        String shortcutId = "profile_" + profileKey.replace("-", "_");

        if (SDK_INT >= VERSION_CODES.O) {
            createShortcutO(context, shortcutId, shortcutName, icon, profileKey);
        } else if (SDK_INT >= VERSION_CODES.N_MR1) {
            createShortcutN(context, shortcutId, shortcutName, icon, profileKey);
        } else {
            createShortcutLegacy(context, shortcutName, icon, profileKey);
        }

        // Remember this shortcut was created
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(PREFS_KEY_SHORTCUTS + "_" + profileKey, shortcutName)
                .apply();

        Log.d(TAG, "Shortcut created for profile: " + profileKey);
    }

    /**
     * Remove a previously created shortcut.
     * Note: On API 25+, we can disable dynamic shortcuts.
     * For pinned shortcuts, removal is handled by the launcher.
     */
    public static void removeShortcut(@NonNull Context context,
                                       @NonNull String profileKey) {
        if (SDK_INT >= VERSION_CODES.N_MR1) {
            ShortcutManager shortcutManager =
                    context.getSystemService(ShortcutManager.class);
            if (shortcutManager != null) {
                String shortcutId = "profile_" + profileKey.replace("-", "_");
                shortcutManager.disableShortcuts(
                        java.util.Collections.singletonList(shortcutId));
            }
        }

        LauncherPreferences.DEFAULT_PREF.edit()
                .remove(PREFS_KEY_SHORTCUTS + "_" + profileKey)
                .apply();

        // Clean up cached icon
        deleteShortcutIcon(context, profileKey);
    }

    /**
     * Check if a shortcut exists for the given profile.
     */
    public static boolean hasShortcut(@NonNull Context context,
                                       @NonNull String profileKey) {
        return LauncherPreferences.DEFAULT_PREF
                .contains(PREFS_KEY_SHORTCUTS + "_" + profileKey);
    }

    /**
     * Get the custom name for a shortcut (if set).
     */
    @Nullable
    public static String getShortcutName(@NonNull Context context,
                                          @NonNull String profileKey) {
        String name = LauncherPreferences.DEFAULT_PREF
                .getString(PREFS_KEY_SHORTCUTS + "_" + profileKey, null);
        return name != null && !name.isEmpty() ? name : null;
    }

    // ─── API 26+ path ───────────────────────────────────────────────────

    private static void createShortcutO(@NonNull Context context,
                                         @NonNull String shortcutId,
                                         @NonNull String name,
                                         @NonNull Bitmap icon,
                                         @NonNull String profileKey) {
        ShortcutManager shortcutManager =
                context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null) return;

        Intent intent = new Intent(context, ShortcutActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(ShortcutActivity.EXTRA_PROFILE_KEY, profileKey);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP);

        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithBitmap(icon))
                .setIntent(intent)
                .build();

        // On API 33+ the one-arg overload is removed; use the two-arg version with null callback.
        // On API <33 the one-arg method exists at runtime but is invisible to the SDK 34 compiler,
        // so we call it via reflection.
        if (SDK_INT >= VERSION_CODES.TIRAMISU) {
            shortcutManager.requestPinShortcut(shortcut, null);
        } else {
            try {
                Method oneArg = ShortcutManager.class.getMethod(
                        "requestPinShortcut", ShortcutInfo.class);
                oneArg.invoke(shortcutManager, shortcut);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create shortcut via reflection", e);
            }
        }
    }

    // ─── API 25 path ────────────────────────────────────────────────────

    private static void createShortcutN(@NonNull Context context,
                                         @NonNull String shortcutId,
                                         @NonNull String name,
                                         @NonNull Bitmap icon,
                                         @NonNull String profileKey) {
        ShortcutManager shortcutManager =
                context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null) return;

        Intent intent = new Intent(context, ShortcutActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(ShortcutActivity.EXTRA_PROFILE_KEY, profileKey);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP);

        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithBitmap(icon))
                .setIntent(intent)
                .build();

        // On API 33+ the one-arg overload is removed; use the two-arg version with null callback.
        // On API <33 the one-arg method exists at runtime but is invisible to the SDK 34 compiler,
        // so we call it via reflection.
        if (SDK_INT >= VERSION_CODES.TIRAMISU) {
            shortcutManager.requestPinShortcut(shortcut, null);
        } else {
            try {
                Method oneArg = ShortcutManager.class.getMethod(
                        "requestPinShortcut", ShortcutInfo.class);
                oneArg.invoke(shortcutManager, shortcut);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create shortcut via reflection", e);
            }
        }
    }

    // ─── API 21-24 legacy path ─────────────────────────────────────────

    private static void createShortcutLegacy(@NonNull Context context,
                                              @NonNull String name,
                                              @NonNull Bitmap icon,
                                              @NonNull String profileKey) {
        Intent shortcutIntent = new Intent(context, ShortcutActivity.class);
        shortcutIntent.setAction(Intent.ACTION_VIEW);
        shortcutIntent.putExtra(ShortcutActivity.EXTRA_PROFILE_KEY, profileKey);
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP);

        Intent addIntent = new Intent();
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
        addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");

        context.sendBroadcast(addIntent);
    }

    // ─── Icon resolution ───────────────────────────────────────────────

    /**
     * Resolve a profile icon as a Bitmap suitable for shortcuts.
     * Tries ProfileIconCache first, then falls back to a default.
     */
    @Nullable
    public static Bitmap resolveProfileIcon(@NonNull Context context,
                                             @NonNull String profileKey,
                                             @NonNull MinecraftProfile profile) {
        try {
            Drawable drawable = ProfileIconCache.fetchIcon(
                    context.getResources(), profileKey, profile.icon);
            if (drawable != null) {
                return drawableToBitmap(drawable);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load profile icon", e);
        }

        // Fallback: use launcher icon
        Drawable fallback = context.getDrawable(R.drawable.ic_pojav_full);
        if (fallback != null) {
            return drawableToBitmap(fallback);
        }

        return null;
    }

    /**
     * Load a previously saved shortcut icon from internal storage.
     */
    @Nullable
    public static Bitmap loadShortcutIcon(@NonNull Context context,
                                           @NonNull String profileKey) {
        File iconFile = getIconFile(context, profileKey);
        if (iconFile.exists()) {
            return BitmapFactory.decodeFile(iconFile.getAbsolutePath());
        }
        return null;
    }

    // ─── Internal helpers ──────────────────────────────────────────────

    private static void saveShortcutIcon(@NonNull Context context,
                                          @NonNull String profileKey,
                                          @NonNull Bitmap icon) {
        File iconFile = getIconFile(context, profileKey);
        File parent = iconFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(iconFile)) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.w(TAG, "Failed to save shortcut icon", e);
        }
    }

    private static void deleteShortcutIcon(@NonNull Context context,
                                            @NonNull String profileKey) {
        File iconFile = getIconFile(context, profileKey);
        if (iconFile.exists()) {
            iconFile.delete();
        }
    }

    private static File getIconFile(@NonNull Context context,
                                     @NonNull String profileKey) {
        File dir = new File(context.getFilesDir(), SHORTCUTS_DIR);
        return new File(dir, "shortcut_" + profileKey.replace("-", "_") + ".png");
    }

    @Nullable
    private static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        int width = Math.max(drawable.getIntrinsicWidth(), 1);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);

        // Limit icon size for shortcuts (typically 96dp or 72dp)
        int maxSize = 128;
        if (width > maxSize || height > maxSize) {
            float ratio = Math.min((float) maxSize / width, (float) maxSize / height);
            width = (int) (width * ratio);
            height = (int) (height * ratio);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
}
