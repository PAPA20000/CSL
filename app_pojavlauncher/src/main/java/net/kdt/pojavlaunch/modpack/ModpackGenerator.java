package net.kdt.pojavlaunch.modpack;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Generates a modpack from the builder's finalised {@link BuilderState}:
 * 1. Creates a new {@link MinecraftProfile} with the correct version & loader
 * 2. Sets up the game directory and folder structure (mods/, resourcepacks/, shaderpacks/)
 * 3. Writes placeholders for selected mods / resource packs / shaders
 * 4. Saves the profile to launcher_profiles.json
 */
public class ModpackGenerator {

    private static final String TAG = "ModpackGenerator";
    private static final String MODPACKS_DIR = "/modpacks";

    private final Context mContext;
    private final BuilderState mState;

    public ModpackGenerator(Context context, BuilderState state) {
        this.mContext = context;
        this.mState = state;
    }

    /**
     * Construct a generator from a JSON manifest (import from share code).
     * Parses the manifest into a BuilderState so {@link #generate()} can run identically.
     */
    public ModpackGenerator(Context context, JSONObject manifest) throws JSONException {
        this.mContext = context;
        this.mState = new BuilderState();
        mState.modpackName = manifest.optString("name", "Imported Modpack");
        mState.modpackDescription = manifest.optString("description", "");
        mState.selectedVersionId = manifest.optString("mcVersion", "1.21");
        mState.selectedLoader = manifest.optString("loader", "vanilla");

        // Mods
        JSONArray mods = manifest.optJSONArray("mods");
        if (mods != null) {
            for (int i = 0; i < mods.length(); i++) {
                JSONObject m = mods.getJSONObject(i);
                BuilderState.ModEntry entry = new BuilderState.ModEntry(
                        m.optString("name", ""),
                        m.optString("fileName", ""),
                        m.optString("description", ""));
                entry.sourceUrl = m.optString("url", null);
                entry.enabled = m.optBoolean("enabled", true);
                entry.recommended = m.optBoolean("recommended", false);
                mState.selectedMods.add(entry);
            }
        }

        // Resource packs
        JSONArray rps = manifest.optJSONArray("resourcePacks");
        if (rps != null) {
            for (int i = 0; i < rps.length(); i++) {
                JSONObject r = rps.getJSONObject(i);
                BuilderState.ModEntry entry = new BuilderState.ModEntry(
                        r.optString("name", ""),
                        r.optString("fileName", ""),
                        r.optString("description", ""));
                entry.sourceUrl = r.optString("url", null);
                entry.enabled = r.optBoolean("enabled", true);
                entry.recommended = r.optBoolean("recommended", false);
                mState.selectedResourcePacks.add(entry);
            }
        }

        // Shaders
        JSONArray shaders = manifest.optJSONArray("shaders");
        if (shaders != null) {
            for (int i = 0; i < shaders.length(); i++) {
                JSONObject s = shaders.getJSONObject(i);
                BuilderState.ModEntry entry = new BuilderState.ModEntry(
                        s.optString("name", ""),
                        s.optString("fileName", ""),
                        s.optString("description", ""));
                entry.sourceUrl = s.optString("url", null);
                entry.enabled = s.optBoolean("enabled", true);
                entry.recommended = s.optBoolean("recommended", false);
                mState.selectedShaders.add(entry);
            }
        }
    }

    /**
     * Serialise a BuilderState into a JSON manifest for share-code export.
     */
    public static JSONObject toManifest(BuilderState state) throws JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("name", state.modpackName != null ? state.modpackName : "");
        manifest.put("description", state.modpackDescription != null ? state.modpackDescription : "");
        manifest.put("mcVersion", state.selectedVersionId != null ? state.selectedVersionId : "");
        manifest.put("loader", state.selectedLoader != null ? state.selectedLoader : "vanilla");

        // Mods
        JSONArray mods = new JSONArray();
        for (BuilderState.ModEntry m : state.selectedMods) {
            if (!m.enabled) continue;
            JSONObject mo = new JSONObject();
            mo.put("name", m.name != null ? m.name : "");
            mo.put("fileName", m.fileName != null ? m.fileName : "");
            mo.put("description", m.description != null ? m.description : "");
            if (m.sourceUrl != null) mo.put("url", m.sourceUrl);
            mo.put("enabled", true);
            mo.put("recommended", m.recommended);
            mods.put(mo);
        }
        manifest.put("mods", mods);

        // Resource packs
        JSONArray rps = new JSONArray();
        for (BuilderState.ModEntry r : state.selectedResourcePacks) {
            if (!r.enabled) continue;
            JSONObject ro = new JSONObject();
            ro.put("name", r.name != null ? r.name : "");
            ro.put("fileName", r.fileName != null ? r.fileName : "");
            ro.put("description", r.description != null ? r.description : "");
            if (r.sourceUrl != null) ro.put("url", r.sourceUrl);
            ro.put("enabled", true);
            ro.put("recommended", r.recommended);
            rps.put(ro);
        }
        manifest.put("resourcePacks", rps);

        // Shaders
        JSONArray shaders = new JSONArray();
        for (BuilderState.ModEntry s : state.selectedShaders) {
            if (!s.enabled) continue;
            JSONObject so = new JSONObject();
            so.put("name", s.name != null ? s.name : "");
            so.put("fileName", s.fileName != null ? s.fileName : "");
            so.put("description", s.description != null ? s.description : "");
            if (s.sourceUrl != null) so.put("url", s.sourceUrl);
            so.put("enabled", true);
            so.put("recommended", s.recommended);
            shaders.put(so);
        }
        manifest.put("shaders", shaders);

        return manifest;
    }

    /**
     * Run the generation pipeline.
     * @return the profile key of the newly created profile
     * @throws Exception if any step fails
     */
    public String generate() throws Exception {
        Log.i(TAG, "Generating modpack: " + mState.modpackName);

        // 1. Determine the effective version ID (with loader prefix if needed)
        String effectiveVersionId = resolveVersionId();

        // 2. Create game directory
        String gameDirName = sanitiseName(mState.modpackName);
        File gameDir = new File(Tools.DIR_GAME_NEW + MODPACKS_DIR + "/" + gameDirName);
        if (!gameDir.exists()) {
            gameDir.mkdirs();
        }

        // 3. Create folder structure
        createFolderStructure(gameDir);

        // 4. Create the MinecraftProfile
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = mState.modpackName;
        profile.lastVersionId = effectiveVersionId;
        profile.icon = "Grass";
        profile.type = "modpack";
        profile.gameDir = "./modpacks/" + gameDirName;
        profile.created = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                java.util.Locale.US).format(new java.util.Date());
        profile.lastUsed = profile.created;

        // Set renderer default for modpacks
        profile.pojavRendererName = null; // use global default

        // 5. Write mod / resource pack / shader placeholders
        writeModEntries(profile, gameDir);

        // 6. Add description if provided
        if (mState.modpackDescription != null && !mState.modpackDescription.isEmpty()) {
            profile.javaArgs = "# Modpack: " + mState.modpackName
                    + "\n# Description: " + mState.modpackDescription;
        }

        // 7. Insert into profiles
        String profileKey = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.mainProfileJson.profiles.put(profileKey, profile);
        LauncherProfiles.write();

        Log.i(TAG, "Modpack generated: " + profileKey);
        return profileKey;
    }

    /**
     * Resolve the effective version ID.
     * For loader-based modpacks, prepend the loader prefix.
     */
    private String resolveVersionId() {
        String ver = mState.selectedVersionId;
        if (ver == null || ver.isEmpty()) {
            ver = "1.21";
        }

        switch (mState.selectedLoader) {
            case "fabric":
                if (!ver.toLowerCase(java.util.Locale.ROOT).contains("fabric")) {
                    // Use installed fabric version if available, otherwise mark as fabric
                    String fabricVer = findInstalledLoaderVersion("fabric", ver);
                    if (fabricVer != null) return fabricVer;
                    return "fabric-loader-" + ver;
                }
                return ver;
            case "forge":
                if (!ver.toLowerCase(java.util.Locale.ROOT).contains("forge")) {
                    String forgeVer = findInstalledLoaderVersion("forge", ver);
                    if (forgeVer != null) return forgeVer;
                    return ver + "-forge";
                }
                return ver;
            case "neoforge":
                if (!ver.toLowerCase(java.util.Locale.ROOT).contains("neoforge")) {
                    String neoVer = findInstalledLoaderVersion("neoforge", ver);
                    if (neoVer != null) return neoVer;
                    return ver + "-neoforge";
                }
                return ver;
            case "quilt":
                if (!ver.toLowerCase(java.util.Locale.ROOT).contains("quilt")) {
                    String quiltVer = findInstalledLoaderVersion("quilt", ver);
                    if (quiltVer != null) return quiltVer;
                    return "quilt-loader-" + ver;
                }
                return ver;
            default:
                return ver;
        }
    }

    /**
     * Look through installed versions for one matching the given loader prefix and MC version.
     * E.g. findInstalledLoaderVersion("fabric", "1.20.1") -> "fabric-loader-0.15.11-1.20.1"
     */
    private String findInstalledLoaderVersion(String loader, String mcVersion) {
        try {
            File versionsDir = new File(Tools.DIR_GAME_NEW + "/versions");
            if (versionsDir.exists() && versionsDir.isDirectory()) {
                File[] dirs = versionsDir.listFiles(File::isDirectory);
                if (dirs != null) {
                    String best = null;
                    for (File dir : dirs) {
                        String name = dir.getName().toLowerCase(java.util.Locale.ROOT);
                        if (name.contains(loader) && name.contains(mcVersion)) {
                            // Prefer longer match (more specific)
                            if (best == null || name.length() > best.length()) {
                                best = dir.getName();
                            }
                        }
                    }
                    return best;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Create the standard Minecraft folder structure inside the modpack directory.
     */
    private void createFolderStructure(File gameDir) {
        String[] folders = {"mods", "resourcepacks", "shaderpacks", "saves", "config"};
        for (String folder : folders) {
            File dir = new File(gameDir, folder);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        // Create a modpack metadata file
        File metaFile = new File(gameDir, "modpack.json");
        if (!metaFile.exists()) {
            try {
                String meta = "{\n"
                        + "  \"name\": \"" + escapeJson(mState.modpackName) + "\",\n"
                        + "  \"description\": \"" + escapeJson(mState.modpackDescription != null ? mState.modpackDescription : "") + "\",\n"
                        + "  \"mcVersion\": \"" + escapeJson(mState.selectedVersionId) + "\",\n"
                        + "  \"loader\": \"" + escapeJson(mState.selectedLoader) + "\",\n"
                        + "  \"created\": \"" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                                java.util.Locale.US).format(new java.util.Date()) + "\"\n"
                        + "}";
                Tools.write(metaFile.getAbsolutePath(), meta);
            } catch (Exception e) {
                Log.w(TAG, "Failed to write modpack metadata", e);
            }
        }
    }

    /**
     * Write placeholder files for selected mods, resource packs, and shaders.
     * Also creates a README listing all selections.
     */
    private void writeModEntries(MinecraftProfile profile, File gameDir) throws Exception {
        StringBuilder readme = new StringBuilder();
        readme.append("Modpack: ").append(mState.modpackName).append("\n");
        readme.append("Version: ").append(mState.selectedVersionId).append("\n");
        readme.append("Loader: ").append(mState.selectedLoader).append("\n\n");

        // Mods
        readme.append("=== MODS ===\n");
        File modsDir = new File(gameDir, "mods");
        for (BuilderState.ModEntry mod : mState.selectedMods) {
            if (!mod.enabled) continue;
            readme.append("- ").append(mod.name);
            if (mod.fileName != null && !mod.fileName.isEmpty()) {
                readme.append(" (").append(mod.fileName).append(")");
            }
            readme.append("\n");
            readme.append("  ").append(mod.description).append("\n");

            // Create placeholder .jar marker
            if (mod.fileName != null && !mod.fileName.isEmpty()) {
                File placeholder = new File(modsDir, mod.fileName + ".url");
                if (!placeholder.exists()) {
                    try {
                        Tools.write(placeholder.getAbsolutePath(),
                                mod.sourceUrl != null && !mod.sourceUrl.isEmpty()
                                        ? mod.sourceUrl : "https://modrinth.com/mod/" + mod.name.toLowerCase());
                    } catch (Exception ignored) {}
                }
            }
        }

        // Resource packs
        readme.append("\n=== RESOURCE PACKS ===\n");
        File rpDir = new File(gameDir, "resourcepacks");
        for (BuilderState.ModEntry rp : mState.selectedResourcePacks) {
            if (!rp.enabled) continue;
            readme.append("- ").append(rp.name).append("\n");
            readme.append("  ").append(rp.description).append("\n");

            if (rp.fileName != null && !rp.fileName.isEmpty()) {
                File placeholder = new File(rpDir, rp.fileName + ".url");
                if (!placeholder.exists()) {
                    try {
                        Tools.write(placeholder.getAbsolutePath(), "");
                    } catch (Exception ignored) {}
                }
            }
        }

        // Shaders
        readme.append("\n=== SHADER PACKS ===\n");
        File shaderDir = new File(gameDir, "shaderpacks");
        for (BuilderState.ModEntry shader : mState.selectedShaders) {
            if (!shader.enabled) continue;
            readme.append("- ").append(shader.name).append("\n");
            readme.append("  ").append(shader.description).append("\n");

            if (shader.fileName != null && !shader.fileName.isEmpty()) {
                File placeholder = new File(shaderDir, shader.fileName + ".url");
                if (!placeholder.exists()) {
                    try {
                        Tools.write(placeholder.getAbsolutePath(), "");
                    } catch (Exception ignored) {}
                }
            }
        }

        // Write README
        File readmeFile = new File(gameDir, "MODPACK_README.txt");
        Tools.write(readmeFile.getAbsolutePath(), readme.toString());
    }

    private static String sanitiseName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("^_|_$", "")
                   .toLowerCase(java.util.Locale.ROOT);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
