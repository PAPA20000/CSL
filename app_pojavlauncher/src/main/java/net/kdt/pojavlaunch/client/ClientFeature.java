package net.kdt.pojavlaunch.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.EnableClientWizardFragment;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Central bridge between CS Launcher and the CS CLIENT mod.
 *
 * Tracks the "Client Feature" state (enabled / MC version / profile) and keeps
 * skin & cape selections synchronized with the mod's own config file
 * ({@code <gameDir>/config/csclient/csclient.json}) so launcher ↔ client stay
 * in sync with a single source of truth.
 */
public final class ClientFeature {
    private static final String TAG = "ClientFeature";

    private static final String PREFS = "client_feature_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MC_VERSION = "mc_version";
    private static final String KEY_PROFILE_KEY = "profile_key";

    /** Latest CS CLIENT release asset (auto-resolved from the GitHub API at install time). */
    public static final String CS_CLIENT_RELEASE_API = "https://api.github.com/repos/PAPA20000/Mod/releases/latest";
    /**
     * Per-version release endpoint. CS CLIENT CI publishes one GitHub release
     * per Minecraft version tagged {@code v<mod>-<mc>}, so we resolve the exact
     * version's release (not "latest", which could be a different MC version).
     */
    public static String csClientReleaseApiFor(String mcVersion) {
        return "https://api.github.com/repos/PAPA20000/Mod/releases/tags/v2.3.0-" + mcVersion;
    }
    /** Modrinth project id of Fabric API. */
    public static final String FABRIC_API_PROJECT_ID = "fabric-api";

    /**
     * Minecraft versions CS CLIENT actually builds for (the 1.21.x line).
     * Each version's Fabric Loader / Fabric API / CS CLIENT jar is resolved and
     * installed automatically. 26.x is a separate unobfuscated generation.
     */
    public static final String[] SUPPORTED_MC_VERSIONS = {
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
            "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
    };

    private ClientFeature() { }

    // ── State ───────────────────────────────────────────────────────────

    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static String getMcVersion(Context context) {
        return getPrefs(context).getString(KEY_MC_VERSION, null);
    }

    public static String getProfileKey(Context context) {
        return getPrefs(context).getString(KEY_PROFILE_KEY, null);
    }

    public static void markEnabled(Context context, String mcVersion, String profileKey) {
        getPrefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_MC_VERSION, mcVersion)
                .putString(KEY_PROFILE_KEY, profileKey)
                .apply();
    }

    public static void disable(Context context) {
        getPrefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_MC_VERSION)
                .remove(KEY_PROFILE_KEY)
                .apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Profile created by the wizard, resolved by its stored key. */
    public static MinecraftProfile resolveClientProfile(Context context) {
        String key = getProfileKey(context);
        if (TextUtils.isEmpty(key)) return null;
        try {
            LauncherProfiles.load();
            return LauncherProfiles.mainProfileJson.profiles.get(key);
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve client profile", e);
            return null;
        }
    }

    // ── Wizard entry ────────────────────────────────────────────────────

    /** Opens the premium "Enable Client Feature" wizard. */
    public static void openWizard(FragmentActivity activity) {
        Tools.swapFragment(activity,
                EnableClientWizardFragment.class, EnableClientWizardFragment.TAG, null,
                R.anim.fade_in_slide_up, R.anim.fade_out_slide_down,
                R.anim.fade_in_slide_up, R.anim.fade_out_slide_down);
    }

    // ── Skin & Cape sync (launcher ↔ mod) ───────────────────────────────

    /**
     * Shared skin/cape folders used by both the launcher and the mod.
     * Files dropped here are instantly usable by CS CLIENT in-game.
     */
    public static File skinFolder(File gameDir) {
        return new File(gameDir, "skins");
    }

    public static File capeFolder(File gameDir) {
        return new File(gameDir, "capes");
    }

    /**
     * Path of the mod's config file. The mod reads its selections from here,
     * so the launcher writes into it to keep both sides synchronized.
     */
    public static File modConfigFile(File gameDir) {
        return new File(gameDir, "config/csclient/csclient.json");
    }

    /** Returns the selected skin file name, or null. */
    public static String getSkinSelection(File gameDir) {
        return readModConfig(gameDir, "skinFile");
    }

    /** Returns the selected cape file name, or null. */
    public static String getCapeSelection(File gameDir) {
        return readModConfig(gameDir, "capeFile");
    }

    /** Returns the selected skin model ("wide"/"slim"), default "wide". */
    public static String getSkinModel(File gameDir) {
        String model = readModConfig(gameDir, "skinModel");
        return model != null ? model : "wide";
    }

    /**
     * Applies a skin/cape selection to the shared folders and writes the mod
     * config so CS CLIENT picks it up on the very next frame. Returns the mod
     * config path that was written, or null when nothing was written.
     */
    public static File applySkinCape(File gameDir, String skinFile, String capeFile, String model) {
        if (gameDir == null) return null;
        try {
            skinFolder(gameDir).mkdirs();
            capeFolder(gameDir).mkdirs();

            File config = modConfigFile(gameDir);
            config.getParentFile().mkdirs();
            JsonObject root = new JsonObject();
            if (config.isFile()) {
                try {
                    JsonObject parsed = JsonParser.parseString(
                            new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (parsed != null) root = parsed;
                } catch (Exception ignored) { }
            }
            if (skinFile != null) root.addProperty("skinFile", skinFile);
            if (capeFile != null) root.addProperty("capeFile", capeFile);
            if (model != null) root.addProperty("skinModel", model);
            Files.write(config.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Skin/cape config synchronized: " + config.getAbsolutePath());
            return config;
        } catch (IOException e) {
            Log.w(TAG, "Could not sync skin/cape config", e);
            return null;
        }
    }

    private static String readModConfig(File gameDir, String key) {
        if (gameDir == null) return null;
        File config = modConfigFile(gameDir);
        if (!config.isFile()) return null;
        try {
            JsonObject root = JsonParser.parseString(
                    new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return (root != null && root.has(key)) ? root.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
