package dev.ultima.lab;

import dev.ultima.config.UltimaModules;
import java.util.List;

/**
 * Named gates for the isolated code-completion lab. Every performance-changing
 * experiment is independently switchable and default-off.
 *
 * <pre>
 * data_mesher                  independent
 * compact_terrain_vertices     independent
 * command_compaction           requires retained_terrain
 * retained_visibility          requires retained_terrain
 * </pre>
 *
 * {@code compact_terrain_vertices} does not require {@code data_mesher}. When both
 * are on, the mesher may emit compact vertices directly; when only compact is on,
 * vanilla {@code MeshData} can be converted after compile.
 *
 * {@code data_mesher} does not require {@code java_mesher}. When both are requested,
 * {@code data_mesher} wins.
 */
public final class LabFeatureGates {
    public static final String DATA_MESHER = "data_mesher";
    public static final String COMPACT_TERRAIN_VERTICES = "compact_terrain_vertices";
    public static final String COMMAND_COMPACTION = "command_compaction";
    public static final String RETAINED_VISIBILITY = "retained_visibility";
    public static final String RETAINED_TERRAIN = "retained_terrain";
    public static final String JAVA_MESHER = "java_mesher";

    private LabFeatureGates() {
    }

    public static List<String> allLabKeys() {
        return List.of(DATA_MESHER, COMPACT_TERRAIN_VERTICES, COMMAND_COMPACTION, RETAINED_VISIBILITY);
    }

    public static UltimaModules.Module require(final String key) {
        UltimaModules.Module module = UltimaModules.byKey(key);
        if (module == null) {
            throw new IllegalStateException("lab gate '" + key + "' is not registered");
        }
        return module;
    }
}
