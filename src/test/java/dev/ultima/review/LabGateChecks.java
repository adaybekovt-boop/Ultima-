package dev.ultima.review;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import dev.ultima.lab.CommandCompactionPolicy;
import dev.ultima.lab.LabFeatureGates;
import dev.ultima.visibility.VisibilityMask;
import dev.ultima.visibility.gpu.GpuVisibilityContract;
import java.lang.reflect.Constructor;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class LabGateChecks {
    private LabGateChecks() {
    }

    static void run() {
        testLabModulesRegisteredAndDefaultOff();
        testDependencyGraph();
        testRendererFamilyIncompatibility();
        testCommandCompactionPolicy();
        testGpuVisibilityContract();
        testVisibilityMaskBounds();
    }

    private static void testLabModulesRegisteredAndDefaultOff() {
        for (String key : LabFeatureGates.allLabKeys()) {
            UltimaModules.Module module = LabFeatureGates.require(key);
            if (module.enabledByDefault()) {
                throw new AssertionError(key + " must default OFF");
            }
            if (!module.clientOnly()) {
                throw new AssertionError(key + " must be client-only");
            }
        }
        if (UltimaModules.byKey(LabFeatureGates.DATA_MESHER) == null) {
            throw new AssertionError("data_mesher must be registered");
        }
    }

    private static void testDependencyGraph() {
        UltimaModules.Module compact = LabFeatureGates.require(LabFeatureGates.COMPACT_TERRAIN_VERTICES);
        if (!compact.dependencies().equals(List.of(LabFeatureGates.RETAINED_TERRAIN))) {
            throw new AssertionError("compact_terrain_vertices GPU path requires retained_terrain, got "
                    + compact.dependencies());
        }
        UltimaModules.Module mesher = LabFeatureGates.require(LabFeatureGates.DATA_MESHER);
        if (!mesher.dependencies().isEmpty()) {
            throw new AssertionError("data_mesher must not require java_mesher");
        }
        UltimaModules.Module compaction = LabFeatureGates.require(LabFeatureGates.COMMAND_COMPACTION);
        if (!compaction.dependencies().equals(List.of(LabFeatureGates.RETAINED_TERRAIN))) {
            throw new AssertionError("command_compaction requires retained_terrain, got " + compaction.dependencies());
        }
        UltimaModules.Module visibility = LabFeatureGates.require(LabFeatureGates.RETAINED_VISIBILITY);
        if (!visibility.dependencies().equals(List.of(LabFeatureGates.RETAINED_TERRAIN))) {
            throw new AssertionError("retained_visibility requires retained_terrain, got " + visibility.dependencies());
        }

        try {
            Constructor<UltimaConfig> constructor = UltimaConfig.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            Map<String, Boolean> requested = new LinkedHashMap<>();
            for (UltimaModules.Module module : UltimaModules.all()) {
                requested.put(module.key(), false);
            }
            requested.put(LabFeatureGates.COMMAND_COMPACTION, true);
            requested.put(LabFeatureGates.RETAINED_VISIBILITY, true);
            requested.put(LabFeatureGates.COMPACT_TERRAIN_VERTICES, true);
            requested.put(LabFeatureGates.DATA_MESHER, true);
            requested.put(LabFeatureGates.RETAINED_TERRAIN, false);
            UltimaConfig config = constructor.newInstance(requested);
            if (config.isEnabled(LabFeatureGates.COMMAND_COMPACTION)) {
                throw new AssertionError("command_compaction must stay off without retained_terrain");
            }
            if (config.isEnabled(LabFeatureGates.RETAINED_VISIBILITY)) {
                throw new AssertionError("retained_visibility must stay off without retained_terrain");
            }
            UltimaConfig.ResolvedModule compactionResolved = config.resolve(LabFeatureGates.COMMAND_COMPACTION);
            String reason = compactionResolved.reason();
            if (!"dependency_disabled".equals(reason) && !"not_client_environment".equals(reason)) {
                throw new AssertionError("command_compaction inactive reason should be dependency or client env, got "
                        + reason);
            }
            if ("dependency_disabled".equals(reason)
                    && !LabFeatureGates.RETAINED_TERRAIN.equals(compactionResolved.blockingDependency())) {
                throw new AssertionError("blocking dependency must be retained_terrain");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not construct UltimaConfig for lab gates", e);
        }
    }

    private static void testRendererFamilyIncompatibility() {
        for (String key : LabFeatureGates.allLabKeys()) {
            List<String> mods = LabFeatureGates.require(key).incompatibleMods();
            for (String renderer : List.of("sodium", "iris", "canvas")) {
                if (!mods.contains(renderer)) {
                    throw new AssertionError(key + " must auto-disable when " + renderer + " is loaded");
                }
            }
        }
    }

    private static void testCommandCompactionPolicy() {
        CommandCompactionPolicy policy = new CommandCompactionPolicy(0.5, 64);
        if (policy.shouldCompact(100, 100)) {
            throw new AssertionError("no dead commands must not compact");
        }
        if (policy.shouldCompact(100, 60)) {
            throw new AssertionError("40 dead below minDead=64 must not compact");
        }
        if (!policy.shouldCompact(200, 80)) {
            throw new AssertionError("120/200 dead ratio 0.6 with minDead 64 must compact");
        }
        if (policy.shouldCompact(0, 0)) {
            throw new AssertionError("empty command buffer must not compact");
        }
        CommandCompactionPolicy explicit = new CommandCompactionPolicy(0.25, 4);
        if (!explicit.shouldCompact(8, 4)) {
            throw new AssertionError("explicit 0.25/4 policy must compact 4 dead of 8");
        }
        Properties properties = new Properties();
        properties.setProperty(CommandCompactionPolicy.DEAD_RATIO_PROPERTY, "0.75");
        properties.setProperty(CommandCompactionPolicy.MIN_DEAD_PROPERTY, "10");
        CommandCompactionPolicy parsed = CommandCompactionPolicy.fromProperties(properties);
        if (parsed.deadRatioThreshold() != 0.75 || parsed.minDead() != 10) {
            throw new AssertionError("property parse mismatch");
        }
        if (CommandCompactionPolicy.deadRatio(4, 1) != 0.75) {
            throw new AssertionError("deadRatio 3/4");
        }
    }

    private static void testGpuVisibilityContract() {
        BitSet applied = new BitSet();
        int[] appliedCount = {0};
        GpuVisibilityContract.transfer(
                (dest, slotCount) -> {
                    dest.set(2);
                    dest.set(5);
                    dest.set(9);
                },
                (visible, slotCount) -> {
                    applied.clear();
                    applied.or(visible);
                    appliedCount[0] = slotCount;
                },
                8);
        if (appliedCount[0] != 8) {
            throw new AssertionError("slotCount must pass through");
        }
        if (!applied.get(2) || !applied.get(5) || applied.get(9) || applied.get(1)) {
            throw new AssertionError("mask must clip to slotCount and preserve vanilla bits: " + applied);
        }
    }

    private static void testVisibilityMaskBounds() {
        VisibilityMask mask = new VisibilityMask();
        mask.ensureSlots(4);
        mask.setVisible(1, true);
        mask.setVisible(3, true);
        if (mask.visibleCount() != 2 || mask.isVisible(0) || !mask.isVisible(1) || mask.isVisible(4)) {
            throw new AssertionError("visibility mask occupancy");
        }
        BitSet copy = new BitSet();
        mask.copyInto(copy);
        if (!copy.get(1) || !copy.get(3)) {
            throw new AssertionError("copyInto lost bits");
        }
        mask.ensureSlots(2);
        if (mask.isVisible(3) || mask.slotCount() != 2) {
            throw new AssertionError("shrinking slotCount must drop out-of-range bits");
        }
    }
}
