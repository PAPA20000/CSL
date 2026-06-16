package net.kdt.pojavlaunch.modpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggests companion mods based on the selected Minecraft version and loader.
 * Used in Step 3 (Mods) to pre-populate a curated selection of well-known,
 * performance-enhancing and utility mods.
 *
 * Each entry maps to a {@link BuilderState.ModEntry}.
 */
public final class SmartRecommender {

    private SmartRecommender() {}

    /**
     * A single recommended mod entry.
     */
    public static class RecMod {
        public final String name;
        public final String fileName;     // placeholder file name
        public final String description;
        public final String loaders;      // "fabric", "forge", "all"
        public final String minVersion;   // minimum MC version, e.g. "1.16"
        public final int priority;        // higher = more recommended; 0 = not auto-selected

        public RecMod(String name, String fileName, String description,
                      String loaders, String minVersion, int priority) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.loaders = loaders;
            this.minVersion = minVersion;
            this.priority = priority;
        }
    }

    /* ────────────── Performance mods ────────────── */
    private static final RecMod[] PERFORMANCE_MODS = {
        new RecMod("Sodium",          "sodium-fabric.jar",       "Dramatically improves rendering performance (Fabric).",            "fabric",    "1.16", 10),
        new RecMod("Lithium",         "lithium.jar",             "General-purpose server-side optimizations (works client-side).",  "all",       "1.16",  8),
        new RecMod("Phosphor",        "phosphor.jar",            "Light engine optimizations (superseded by Sodium 0.4+).",         "all",       "1.16",  5),
        new RecMod("FerriteCore",     "ferritecore.jar",         "Reduces memory usage by compacting data structures.",              "all",       "1.17",  7),
        new RecMod("Starlight",       "starlight.jar",           "Rewrites the light engine for faster chunk generation.",           "all",       "1.16",  6),
        new RecMod("LazyDFU",         "lazydfu.jar",             "Defers DataFixerUpper initialisation, faster startup.",            "all",       "1.16",  7),
        new RecMod("SmoothBoot",      "smoothboot.jar",          "Parallelises startup tasks for faster loading.",                   "all",       "1.16",  5),
        new RecMod("EntityCulling",   "entityculling.jar",       "Hides entities/block entities not visible by the camera.",         "all",       "1.16",  8),
        new RecMod("Cull Leaves",     "cullleaves.jar",          "Improves FPS by culling leaf faces.",                              "all",       "1.16",  6),
        new RecMod("DashLoader",      "dashloader.jar",          "Caches and accelerates game loading (alpha).",                     "fabric",    "1.18",  4),
        new RecMod("ImmediatelyFast", "immediatelyfast.jar",     "Optimises immediate-mode rendering calls.",                        "all",       "1.18",  7),
        new RecMod("ModernFix",       "modernfix.jar",           "Fixes various performance and memory issues on modern Java.",      "all",       "1.16",  8),
    };

    /* ────────────── Visual / Shader mods ────────────── */
    private static final RecMod[] VISUAL_MODS = {
        new RecMod("Iris",            "iris-sodium.jar",         "Shader pack support via Sodium (Fabric).",                         "fabric",    "1.16",  9),
        new RecMod("Oculus",          "oculus.jar",              "Shader pack support via Embeddium (Forge).",                       "forge",     "1.18",  9),
        new RecMod("Continuity",      "continuity.jar",          "Connected textures (Fabric).",                                     "fabric",    "1.16",  6),
        new RecMod("LambdaBetterGrass","lambdabettergrass.jar",  "Better grass block connections (Fabric).",                         "fabric",    "1.16",  4),
    };

    /* ────────────── Utility mods ────────────── */
    private static final RecMod[] UTILITY_MODS = {
        new RecMod("Mod Menu",        "modmenu.jar",             "Lists installed mods in-game (Fabric).",                           "fabric",    "1.14",  7),
        new RecMod("Roughly Enough Items", "rei.jar",            "Item and recipe viewer.",                                          "all",       "1.16",  8),
        new RecMod("Just Enough Items",    "jei.jar",            "Item and recipe viewer (Forge/NeoForge).",                         "forge",     "1.0",   8),
        new RecMod("AppleSkin",       "appleskin.jar",           "Shows saturation and hunger preview.",                             "all",       "1.12",  5),
        new RecMod("Jade",            "jade.jar",                "Shows what block/entity you are looking at.",                      "all",       "1.16",  6),
        new RecMod("MiniHUD",         "minihud.jar",             "Customisable on-screen info overlay.",                             "all",       "1.16",  4),
        new RecMod("Xaero's Minimap", "xaeros-minimap.jar",      "Minimap and world map.",                                           "all",       "1.12",  6),
        new RecMod("Connectivity",    "connectivity.jar",        "Fixes common multiplayer connection issues.",                       "all",       "1.16",  5),
    };

    /* ────────────── Resource packs ────────────── */
    private static final RecMod[] RESOURCE_PACKS = {
        new RecMod("Faithful",                 "Faithful_x64.zip",               "Classic high-resolution faithful texture pack.",          "all", "1.0",  8),
        new RecMod("VanillaTweaks",            "VanillaTweaks.zip",              "Customizable vanilla-style tweaks.",                      "all", "1.0",  7),
        new RecMod("Stay True",                "Stay_True.zip",                  "Smoother, rounder vanilla-style textures.",               "all", "1.0",  7),
        new RecMod("Bare Bones",               "Bare_Bones.zip",                 "Minimalist texture pack with flat shading.",               "all", "1.0",  6),
        new RecMod("Mizuno's 16",              "Mizuno_16.zip",                  "Anime-inspired 16x textures.",                             "all", "1.0",  5),
        new RecMod("Jappa's Programmer Art",   "Programmer_Art.zip",             "Backport of pre-1.14 programmer art textures.",            "all", "1.0",  5),
        new RecMod("Compliance",               "Compliance.zip",                  "Community-driven vanilla-like textures.",                   "all", "1.0",  5),
        new RecMod("Dokucraft",                "Dokucraft.zip",                   "Fantasy RPG-themed texture pack.",                         "all", "1.0",  4),
    };

    /* ────────────── Shader packs ────────────── */
    private static final RecMod[] SHADER_PACKS = {
        new RecMod("Complementary Shaders",    "Complementary.zip",              "Vibrant, well-optimised shaders. Needs Iris/Oculus.",       "all", "1.16", 9),
        new RecMod("BSL Shaders",              "BSL.zip",                        "Highly customisable, realistic shaders.",                   "all", "1.16", 9),
        new RecMod("SEUS PTGI",               "SEUS_PTGI.zip",                  "Path-traced shaders. Very demanding.",                      "all", "1.16", 8),
        new RecMod("Chocapic13",               "Chocapic13.zip",                 "Performance-friendly shaders at various profiles.",          "all", "1.16", 8),
        new RecMod("Sildur's Vibrant",         "Sildurs_Vibrant.zip",            "Colourful shaders. Good balance of looks and performance.",  "all", "1.16", 8),
        new RecMod("MakeUp Ultra Fast",        "MakeUp_UltraFast.zip",           "Ultra performance shaders for low-end devices.",             "all", "1.16", 7),
        new RecMod("Rethinking Voxels",        "Rethinking_Voxels.zip",          "PBR-based realistic shaders.",                              "all", "1.16", 7),
        new RecMod("ProjectLUMA",              "ProjectLUMA.zip",                "Cinematic colour grading shaders.",                         "all", "1.16", 6),
    };

    /* ────────────── Public API ────────────── */

    /**
     * Get recommended mods for a given loader and minimum MC version.
     * Mods with priority >= 7 are pre-selected (enabled=true).
     */
    public static List<BuilderState.ModEntry> getRecommendedMods(String loader, String mcVersion) {
        List<BuilderState.ModEntry> result = new ArrayList<>();
        for (RecMod mod : PERFORMANCE_MODS) addIfCompatible(result, mod, loader, mcVersion);
        for (RecMod mod : VISUAL_MODS) addIfCompatible(result, mod, loader, mcVersion);
        for (RecMod mod : UTILITY_MODS) addIfCompatible(result, mod, loader, mcVersion);
        return result;
    }

    /** Get recommended resource packs (independent of loader/version). */
    public static List<BuilderState.ModEntry> getRecommendedResourcePacks() {
        List<BuilderState.ModEntry> result = new ArrayList<>();
        for (RecMod rp : RESOURCE_PACKS) {
            BuilderState.ModEntry entry = new BuilderState.ModEntry(rp.name, rp.fileName, rp.description);
            entry.enabled = rp.priority >= 8;
            entry.sourceUrl = "";
            result.add(entry);
        }
        return result;
    }

    /** Get recommended shader packs. */
    public static List<BuilderState.ModEntry> getRecommendedShaders() {
        List<BuilderState.ModEntry> result = new ArrayList<>();
        for (RecMod shader : SHADER_PACKS) {
            BuilderState.ModEntry entry = new BuilderState.ModEntry(shader.name, shader.fileName, shader.description);
            entry.enabled = false; // never auto-select shaders
            entry.sourceUrl = "";
            result.add(entry);
        }
        return result;
    }

    /* ────────────── Helpers ────────────── */

    private static void addIfCompatible(List<BuilderState.ModEntry> list, RecMod mod,
                                         String loader, String mcVersion) {
        // Check loader compatibility
        boolean loaderOk = "all".equals(mod.loaders) || mod.loaders.equalsIgnoreCase(loader);
        if (!loaderOk) return;

        // Check version compatibility (simple prefix match)
        if (mcVersion != null && !mcVersion.isEmpty()) {
            try {
                String[] parts = mcVersion.split("\\.");
                String[] minParts = mod.minVersion.split("\\.");
                int maj = Integer.parseInt(parts[0]);
                int minMaj = Integer.parseInt(minParts[0]);
                if (maj < minMaj) return;
                if (maj == minMaj && parts.length > 1 && minParts.length > 1) {
                    int minor = Integer.parseInt(parts[1]);
                    int minMin = Integer.parseInt(minParts[1]);
                    if (minor < minMin) return;
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric version, include as-is
            }
        }

        BuilderState.ModEntry entry = new BuilderState.ModEntry(mod.name, mod.fileName, mod.description);
        entry.enabled = mod.priority >= 7; // auto-select high-priority mods
        entry.recommended = mod.priority >= 7;
        entry.sourceUrl = "";
        list.add(entry);
    }
}
