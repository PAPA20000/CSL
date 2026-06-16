package net.kdt.pojavlaunch.modpack;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the full intermediate state of the Modpack Builder 6-step wizard.
 * Serialized as the user progresses so the wizard survives config changes.
 */
public class BuilderState implements Serializable {

    /* ── Step 1: Minecraft version ── */
    public String selectedVersionId;

    /* ── Step 2: Mod loader ── */
    public String selectedLoader;       // "vanilla", "fabric", "forge", "neoforge", "quilt"
    public String selectedLoaderVersion; // optional loader version string

    /* ── Step 3: Mods ── */
    public final List<ModEntry> selectedMods = new ArrayList<>();

    /* ── Step 4: Resource packs ── */
    public final List<ModEntry> selectedResourcePacks = new ArrayList<>();

    /* ── Step 5: Shader packs ── */
    public final List<ModEntry> selectedShaders = new ArrayList<>();

    /* ── Step 6: Finalisation ── */
    public String modpackName;
    public String modpackDescription;

    /** Current wizard step (0-based). */
    public int currentStep;

    /** Whether the builder has been completed and the profile generated. */
    public boolean generated;

    public BuilderState() {
        // Defaults
        selectedLoader = "vanilla";
    }

    /** Lightweight entry for a selected mod / resource pack / shader. */
    public static class ModEntry implements Serializable {
        public String name;
        public String fileName;     // e.g. "sodium-fabric-0.6.0.jar"
        public String description;
        public String sourceUrl;    // optional download URL
        public boolean enabled;
        public boolean recommended; // auto-selected by SmartRecommender

        public ModEntry() {}

        public ModEntry(String name, String fileName, String description) {
            this.name = name;
            this.fileName = fileName;
            this.description = description;
            this.enabled = true;
            this.recommended = false;
        }
    }
}
