package dev.ultima.meshing;

import java.util.List;

/**
 * Hardware A/B scene contract for the mesher. Mesher cost is section
 * <em>build</em> time (load / rebuild / stream-in), not per-frame submit.
 * This class does not measure FPS and must not be quoted as a GPU result.
 */
public final class MesherBenchmarkScenes {
    public enum Scene {
        COLD_WORLD_LOAD(
                "mesher_cold_load",
                "Time to compile N sections on first entry into an unloaded world.",
                "ultima.clientBenchmark.scene=mesher_cold_load"),
        FORCED_REBUILD_STORM(
                "mesher_rebuild_storm",
                "Mass remesh after render-distance change or resource-pack reload.",
                "ultima.clientBenchmark.scene=mesher_rebuild_storm"),
        STEADY_STATE_STREAMING(
                "mesher_chunk_flight",
                "Same chunk-flight route as retained-terrain, scoring meshing time of newly compiled sections.",
                "ultima.clientBenchmark.cameraMode=chunk_flight;ultima.clientBenchmark.scene=mesher_chunk_flight");

        private final String id;
        private final String purpose;
        private final String harnessHint;

        Scene(final String id, final String purpose, final String harnessHint) {
            this.id = id;
            this.purpose = purpose;
            this.harnessHint = harnessHint;
        }

        public String id() {
            return this.id;
        }

        public String purpose() {
            return this.purpose;
        }

        public String harnessHint() {
            return this.harnessHint;
        }
    }

    public static final List<String> HARDWARE_METRICS = List.of(
            "mesherMetrics.meshBuildNs",
            "mesherMetrics.snapshotBuildNs",
            "mesherMetrics.rebuildCount",
            "mesherMetrics.sectionsPerSecond",
            "mesherMetrics.meshBuildNsPerSection",
            "mesherMetrics.fastPathBlocks",
            "mesherMetrics.fallbackBlocks",
            "mesherMetrics.fastPathCoverageOfNonAir",
            "mesherMetrics.fallbackByReason",
            "mesherMetrics.meshFastPathFailures",
            "mesherMetrics.meshFastPathCircuitBreakerTrips",
            "disclaimer");

    private MesherBenchmarkScenes() {
    }

    public static Scene byId(final String id) {
        for (Scene scene : Scene.values()) {
            if (scene.id().equals(id)) {
                return scene;
            }
        }
        throw new IllegalArgumentException("unknown mesher scene " + id);
    }

    public static List<Scene> all() {
        return List.of(Scene.values());
    }
}
