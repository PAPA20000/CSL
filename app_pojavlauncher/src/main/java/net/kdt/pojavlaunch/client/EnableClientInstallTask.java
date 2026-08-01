package net.kdt.pojavlaunch.client;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fully automated CS CLIENT installation:
 *   1. installs the Fabric Loader profile (meta.fabricmc.net)
 *   2. downloads Fabric API (Modrinth)
 *   3. downloads the latest CS CLIENT jar (GitHub release)
 *   4. creates the ready-to-launch "CS CLIENT V1" profile
 *
 * Progress is reported through {@link Listener} so the wizard can animate a
 * premium installation screen.
 */
public class EnableClientInstallTask implements Runnable {
    private static final String TAG = "EnableClientInstall";

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String LOADER_LIST_URL = FABRIC_META + "/versions/loader/%s";
    private static final String LOADER_JSON_URL = FABRIC_META + "/versions/loader/%s/%s/profile/json";
    private static final String FABRIC_API_VERSIONS_URL =
            "https://api.modrinth.com/v2/project/%s/version?game_versions=[%s]&loaders=[\"fabric\"]&limit=1";

    /** Official profile banner for the created profile. */
    public static final String PROFILE_BACKGROUND_URL =
            "https://i.ibb.co/zHR1SSbK/file-00000000f090820899128c8fd2c87e03.png";

    private final String mGameVersion;
    private final Listener mListener;

    public interface Listener {
        void onProgress(int percent, String message);
        void onSuccess(String profileKey, String versionId);
        void onError(Exception e);
    }

    public EnableClientInstallTask(String gameVersion, Listener listener) {
        mGameVersion = gameVersion;
        mListener = listener;
    }

    @Override
    public void run() {
        try {
            String loaderVersion = fetchLatestStableLoader();
            String versionId = installFabricLoader(loaderVersion);
            File gameDir = installClientMods();
            String profileKey = createProfile(versionId, gameDir);
            if (mListener != null) mListener.onSuccess(profileKey, versionId);
        } catch (Exception e) {
            Log.w(TAG, "CS CLIENT install failed", e);
            if (mListener != null) mListener.onError(e);
        }
    }

    private void report(int percent, String message) {
        if (mListener != null) mListener.onProgress(percent, message);
    }

    // ── Step 1: Fabric Loader ───────────────────────────────────────────

    private String fetchLatestStableLoader() throws IOException, JSONException {
        report(5, "Fetching Fabric Loader for " + mGameVersion + "...");
        String body = DownloadUtils.downloadString(String.format(LOADER_LIST_URL, mGameVersion));
        JSONArray versions = new JSONArray(body);
        for (int i = 0; i < versions.length(); i++) {
            JSONObject entry = versions.getJSONObject(i);
            JSONObject loader = entry.has("loader") ? entry.getJSONObject("loader") : entry;
            boolean stable = loader.has("stable") ? loader.getBoolean("stable")
                    : !loader.optString("version").contains("beta");
            if (stable) {
                return loader.getString("version");
            }
        }
        throw new IOException("No stable Fabric Loader found for " + mGameVersion);
    }

    private String installFabricLoader(String loaderVersion) throws IOException, JSONException {
        report(15, "Installing Fabric Loader " + loaderVersion + "...");
        String profileJson = DownloadUtils.downloadString(
                String.format(LOADER_JSON_URL, mGameVersion, loaderVersion));
        JSONObject json = new JSONObject(profileJson);
        String versionId = json.getString("id");

        File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
        FileUtils.ensureDirectory(versionDir);
        File versionJson = new File(versionDir, versionId + ".json");
        if (!versionJson.isFile()) {
            Tools.write(versionJson.getAbsolutePath(), profileJson);
        }
        return versionId;
    }

    // ── Step 2: Fabric API + CS CLIENT jars ─────────────────────────────

    private File installClientMods() throws IOException, JSONException {
        // IMPORTANT: the profile resolves gameDir "./custom_instances/..."
        // relative to DIR_GAME_HOME (see Tools.getGameDirPath). Installing under
        // DIR_GAME_NEW (".minecraft") would put the mods in a folder the game
        // never reads, so CS CLIENT would silently never load. Must match
        // ModpackInstaller/FabriclikeDownloadTask: DIR_GAME_HOME/custom_instances.
        // Each version gets its own instance dir so multiple CS Client versions
        // can coexist, and enabling a new version creates its own profile.
        String instDir = "csclient-" + mGameVersion;
        File gameDir = new File(Tools.DIR_GAME_HOME, "custom_instances/" + instDir);
        File modsDir = new File(gameDir, "mods");
        FileUtils.ensureDirectory(modsDir);

        report(35, "Downloading Fabric API...");
        String encodedVersion = "\"" + mGameVersion + "\"";
        String url = String.format(FABRIC_API_VERSIONS_URL, ClientFeature.FABRIC_API_PROJECT_ID,
                encodedVersion);
        String apiBody = DownloadUtils.downloadString(url);
        JSONArray apiVersions = new JSONArray(apiBody);
        if (apiVersions.length() == 0) {
            throw new IOException("Fabric API has no build for " + mGameVersion);
        }
        JSONObject apiVersion = apiVersions.getJSONObject(0);
        String apiFileUrl = apiVersion.getJSONArray("files").getJSONObject(0).getString("url");
        String apiFileName = "fabric-api-" + apiVersion.optString("version_number", "latest") + ".jar";
        DownloadUtils.downloadFile(apiFileUrl, new File(modsDir, apiFileName));

        report(60, "Downloading CS CLIENT...");
        String csClientUrl = resolveCsClientJarUrl();
        File csClientJar = new File(modsDir, "csclient.jar");
        DownloadUtils.downloadFile(csClientUrl, csClientJar);

        // The mod expects the shared skin/cape folders to exist next to it.
        FileUtils.ensureDirectory(ClientFeature.skinFolder(gameDir));
        FileUtils.ensureDirectory(ClientFeature.capeFolder(gameDir));
        return gameDir;
    }

    /**
     * Resolves the CS CLIENT jar for the SELECTED Minecraft version.
     *
     * The multi-version build system emits one jar per MC version named
     * {@code CSCLIENT-<mcversion>-<modversion>.jar} (e.g. CSCLIENT-1.21.1-2.3.0.jar,
     * CSCLIENT-1.21.11-2.3.0.jar). We match the asset name against mGameVersion so
     * the correct build is installed; a bare {@code CSCLIENT-<mcversion>.jar} is
     * accepted as a fallback, then any {@code CSCLIENT-*.jar} as a last resort.
     */
    private String resolveCsClientJarUrl() throws IOException, JSONException {
        // Prefer the exact per-version release (v<mod>-<mc>), then fall back to
        // releases/latest (for a single-version "latest" build).
        JSONObject release = tryFetchRelease(ClientFeature.csClientReleaseApiFor(mGameVersion));
        if (release == null) {
            release = tryFetchRelease(ClientFeature.CS_CLIENT_RELEASE_API);
        }
        if (release == null) {
            throw new IOException("CS CLIENT release could not be fetched");
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null || assets.length() == 0) {
            throw new IOException("CS CLIENT has no downloadable release asset");
        }
        String exactTarget = "CSCLIENT-" + mGameVersion + "-";
        String primary = null, legacy = null, any = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (!name.endsWith(".jar")) continue;
            if (any == null) any = asset.getString("browser_download_url");
            if (primary == null && name.startsWith(exactTarget)) primary = asset.getString("browser_download_url");
            if (legacy == null && name.equals("CSCLIENT-" + mGameVersion + ".jar")) legacy = asset.getString("browser_download_url");
        }
        if (primary != null) return primary;
        if (legacy != null) return legacy;
        if (any != null) return any; // last-resort single-version build
        throw new IOException("CS CLIENT release has no jar asset");
    }

    /**
     * Returns the release JSON, or null if the endpoint 404s (no such
     * tag/release). GitHub returns a JSON error object (without "assets") on
     * a missing tag, so we only accept payloads that actually carry assets.
     */
    private JSONObject tryFetchRelease(String url) throws IOException, JSONException {
        try {
            String body = DownloadUtils.downloadString(url);
            if (body == null || body.isEmpty()) return null;
            JSONObject obj = new JSONObject(body);
            return obj.has("assets") ? obj : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Step 3: Profile ─────────────────────────────────────────────────

    private String createProfile(String versionId, File gameDir) throws IOException {
        report(85, "Creating profile...");
        LauncherProfiles.load();
        String profileKey = LauncherProfiles.getFreeProfileKey();
        MinecraftProfile profile = MinecraftProfile.createTemplate();
        // One ready-to-launch profile per CS Client version, so each enabled
        // version gets its own profile and instance (no collisions).
        profile.name = "CS CLIENT " + mGameVersion;
        profile.lastVersionId = versionId;
        profile.type = "custom";
        profile.icon = "csclient";
        profile.background = PROFILE_BACKGROUND_URL;
        profile.gameDir = "./custom_instances/csclient-" + mGameVersion;
        // Production-ready profile: recommended memory + stable JVM flags for
        // Fabric 1.21.x, default touch-friendly renderer, so it launches
        // immediately with no manual tuning (Req 7).
        profile.javaArgs = "-Xms256M -Xmx" + recommendedMemoryMegabytes()
                + "M -Dfile.encoding=UTF-8 -XX:+UseG1GC";
        if (profile.pojavRendererName == null) profile.pojavRendererName = "opengl";
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .format(new Date());
        profile.created = now;
        profile.lastUsed = now;

        LauncherProfiles.mainProfileJson.profiles.put(profileKey, profile);
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .apply();
        LauncherProfiles.write();
        return profileKey;
    }

    /**
     * Recommended heap for the selected Minecraft version. Modern versions
     * (1.21.x) need more RAM for the Fabric mod set; cap at 4 GB so low-end
     * Android devices aren't starved. Honours the user's configured total RAM
     * preference when it is lower than the recommended value.
     */
    private String recommendedMemoryMegabytes() {
        int recommended = 2048;
        try {
            String ver = mGameVersion == null ? "" : mGameVersion;
            String[] parts = ver.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                // 1.20 and later default to 2.5 GB; 1.21+ prefer 3 GB.
                if (major > 1 || minor >= 21) recommended = 3072;
                else if (minor >= 20) recommended = 2560;
            }
        } catch (NumberFormatException ignored) { }
        // Respect an explicit user memory ceiling (LauncherPreferences stores MB).
        try {
            int userMax = LauncherPreferences.PREF_RAM_ALLOCATION;
            if (userMax > 0 && userMax < recommended) recommended = userMax;
        } catch (Throwable ignored) { }
        return String.valueOf(recommended);
    }
}
