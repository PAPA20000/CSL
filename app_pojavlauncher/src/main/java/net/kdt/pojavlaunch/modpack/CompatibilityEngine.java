package net.kdt.pojavlaunch.modpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Hardcoded conflict and dependency rules for Minecraft mods.
 * Checks known incompatibilities (e.g. OptiFine vs Sodium) and required
 * companion mods (e.g. Sodium needs Fabric API).
 */
public final class CompatibilityEngine {

    private CompatibilityEngine() {}

    /** A single conflict rule: two mods that must not be enabled together. */
    public static class ConflictRule {
        public final String modA;        // canonical name (lowercase)
        public final String modB;
        public final String reason;

        public ConflictRule(String modA, String modB, String reason) {
            this.modA = modA.toLowerCase();
            this.modB = modB.toLowerCase();
            this.reason = reason;
        }
    }

    /** A dependency hint: one mod strongly recommends or requires another. */
    public static class DependencyHint {
        public final String mod;
        public final String dependsOn;
        public final boolean required;   // true = mandatory, false = strongly recommended
        public final String reason;

        public DependencyHint(String mod, String dependsOn, boolean required, String reason) {
            this.mod = mod.toLowerCase();
            this.dependsOn = dependsOn.toLowerCase();
            this.required = required;
            this.reason = reason;
        }
    }

    /* ────────────── Conflict rules ────────────── */
    private static final ConflictRule[] CONFLICTS = {
        new ConflictRule("OptiFine",          "Sodium",           "OptiFine is incompatible with Sodium. Use Embeddium or Oculus instead."),
        new ConflictRule("OptiFine",          "Iris",             "OptiFine is incompatible with Iris. Use Oculus for Forge/Fabric."),
        new ConflictRule("OptiFine",          "Canvas",           "OptiFine is incompatible with Canvas renderer."),
        new ConflictRule("OptiFine",          "Indium",           "Indium depends on Sodium which conflicts with OptiFine."),
        new ConflictRule("Sodium",            "OptiFine",         "See OptiFine vs Sodium."),
        new ConflictRule("Sodium",            "Phosphor",         "Phosphor functionality is included in Sodium 0.4+."),
        new ConflictRule("Sodium",            "BetterFps",        "BetterFps may cause visual glitches with Sodium."),
        new ConflictRule("Iris",              "OptiFine",         "Iris provides OptiFine shader support. Do not install both."),
        new ConflictRule("Forge",             "Fabric",           "Forge and Fabric are incompatible mod loaders."),
        new ConflictRule("Forge",             "Quilt",            "Forge and Quilt are incompatible mod loaders."),
        new ConflictRule("Fabric",            "Forge",            "See Forge vs Fabric."),
        new ConflictRule("Fabric",            "Quilt",            "Fabric and Quilt are incompatible; Quilt has its own ecosystem."),
        new ConflictRule("Quilt",             "Fabric",           "See Fabric vs Quilt."),
        new ConflictRule("Quilt",             "Forge",            "See Forge vs Quilt."),
        new ConflictRule("NeoForge",          "Forge",            "NeoForge is a fork of Forge; mixing them causes crashes."),
        new ConflictRule("NeoForge",          "Fabric",           "NeoForge and Fabric are incompatible mod loaders."),
        new ConflictRule("NeoForge",          "Quilt",            "NeoForge and Quilt are incompatible mod loaders."),
        new ConflictRule("EntityCulling",     "Sodium",           "EntityCulling 1.6+ includes Sodium integration. No extra setup needed."),
        new ConflictRule("BetterFps",         "Sodium",           "BetterFps is redundant when Sodium is installed."),
        new ConflictRule("BetterFps",         "OptiFine",         "BetterFps is redundant when OptiFine is installed."),
        new ConflictRule("VanillaFix",        "Sodium",           "VanillaFix patches are already included in Sodium."),
        new ConflictRule("FoamFix",           "Sodium",           "FoamFix features are superseded by Sodium."),
    };

    /* ────────────── Dependency hints ────────────── */
    private static final DependencyHint[] DEPENDENCIES = {
        new DependencyHint("Sodium",          "Fabric API",           true,  "Sodium requires Fabric API on Fabric."),
        new DependencyHint("Iris",            "Sodium",               true,  "Iris requires Sodium as a rendering backend."),
        new DependencyHint("Iris",            "Fabric API",           true,  "Iris requires Fabric API on Fabric."),
        new DependencyHint("Lithium",         "Fabric API",           false, "Lithium works best with Fabric API."),
        new DependencyHint("Phosphor",        "Fabric API",           true,  "Phosphor requires Fabric API."),
        new DependencyHint("ModMenu",         "Fabric API",           true,  "Mod Menu requires Fabric API."),
        new DependencyHint("REI",             "Fabric API",           true,  "Roughly Enough Items requires Fabric API on Fabric."),
        new DependencyHint("JEI",             "Forge",                true,  "Just Enough Items is designed for Forge/NeoForge."),
        new DependencyHint("Oculus",          "Rubidium/Embeddium",   true,  "Oculus requires Rubidium (Forge Sodium port)."),
        new DependencyHint("Connectivity",    "Fabric API",           false, "Connectivity benefits from Fabric API."),
        new DependencyHint("FerriteCore",     "(none)",               false, "FerriteCore works on all loaders. No extra dependencies."),
        new DependencyHint("LazyDFU",         "(none)",               false, "LazyDFU is standalone. No dependencies."),
        new DependencyHint("SmoothBoot",      "(none)",               false, "SmoothBoot is standalone. No dependencies."),
        new DependencyHint("Starlight",       "Fabric API",           true,  "Starlight requires Fabric API on Fabric."),
        new DependencyHint("Continuity",      "Fabric API",           true,  "Continuity requires Fabric API."),
        new DependencyHint("EntityCulling",   "(none)",               false, "EntityCulling is standalone. Works on all loaders."),
        new DependencyHint("Cull Leaves",     "(none)",               false, "Cull Leaves is standalone."),
        new DependencyHint("DashLoader",      "Fabric API",           true,  "DashLoader requires Fabric API."),
    };

    /* ────────────── Public API ────────────── */

    /**
     * Check a set of selected mod names for known conflicts.
     * @param modNames lowercase mod names
     * @return list of conflict warnings (may be empty)
     */
    public static List<String> checkConflicts(List<String> modNames) {
        if (modNames == null || modNames.isEmpty()) return Collections.emptyList();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < modNames.size(); i++) {
            for (int j = i + 1; j < modNames.size(); j++) {
                String a = modNames.get(i).toLowerCase();
                String b = modNames.get(j).toLowerCase();
                for (ConflictRule rule : CONFLICTS) {
                    if ((a.contains(rule.modA) && b.contains(rule.modB)) ||
                        (a.contains(rule.modB) && b.contains(rule.modA))) {
                        warnings.add(rule.reason);
                    }
                }
            }
        }
        return warnings;
    }

    /**
     * Check for missing dependencies among selected mods.
     * @param modNames lowercase mod names
     * @return list of dependency warnings (may be empty)
     */
    public static List<String> checkDependencies(List<String> modNames) {
        if (modNames == null || modNames.isEmpty()) return Collections.emptyList();
        List<String> warnings = new ArrayList<>();
        for (String mod : modNames) {
            String lower = mod.toLowerCase();
            for (DependencyHint dep : DEPENDENCIES) {
                if (lower.contains(dep.mod)) {
                    // Skip "none" pseudo-dependency
                    if ("(none)".equals(dep.dependsOn)) continue;
                    // Check if dependency is installed
                    boolean hasDep = false;
                    String depLower = dep.dependsOn.toLowerCase();
                    for (String other : modNames) {
                        if (other.toLowerCase().contains(depLower)) {
                            hasDep = true;
                            break;
                        }
                    }
                    if (!hasDep) {
                        String severity = dep.required ? "REQUIRED" : "RECOMMENDED";
                        warnings.add("[" + severity + "] " + dep.reason);
                    }
                }
            }
        }
        return warnings;
    }

    /**
     * Combined check: returns conflicts AND dependency warnings.
     */
    public static List<String> checkAll(List<String> modNames) {
        List<String> all = new ArrayList<>();
        all.addAll(checkConflicts(modNames));
        all.addAll(checkDependencies(modNames));
        return all;
    }

    /** Return the raw conflict rules for display / debugging. */
    public static ConflictRule[] getConflictRules() { return CONFLICTS; }

    /** Return the raw dependency hints for display / debugging. */
    public static DependencyHint[] getDependencyHints() { return DEPENDENCIES; }
}
