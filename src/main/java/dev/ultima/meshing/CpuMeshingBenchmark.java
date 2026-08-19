package dev.ultima.meshing;

import java.util.Locale;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.CardinalLighting;

/**
 * CPU-only meshing-time comparison of the template fast path against the
 * vanilla-style cube oracle. This is <strong>not</strong> an FPS or GPU claim
 * and must not be quoted as hardware performance.
 */
public final class CpuMeshingBenchmark {
    public record Sample(String name, long oracleNs, long fastPathNs, int vertices, int fastPathBlocks) {
        public double ratio() {
            return this.oracleNs <= 0L ? Double.NaN : (double)this.oracleNs / (double)this.fastPathNs;
        }
    }

    private CpuMeshingBenchmark() {
    }

    public static Sample run(final String name, final PackedSectionVolume volume, final int iterations) {
        PackedLightVolume lights = new PackedLightVolume();
        lights.fill(LightCoordsUtil.pack(15, 15));
        CardinalLighting lighting = CardinalLighting.DEFAULT;
        VanillaCubeOracle.mesh(volume, lights, true, lighting);
        FastPathCubeMesher.mesh(volume, lights, true, lighting);

        long oracleNs = Long.MAX_VALUE;
        long fastNs = Long.MAX_VALUE;
        int vertices = 0;
        int fastBlocks = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            var oracle = VanillaCubeOracle.mesh(volume, lights, true, lighting);
            oracleNs = Math.min(oracleNs, System.nanoTime() - start);
            start = System.nanoTime();
            var fast = FastPathCubeMesher.mesh(volume, lights, true, lighting);
            fastNs = Math.min(fastNs, System.nanoTime() - start);
            vertices = fast.vertices().size();
            fastBlocks = fast.fastPathBlocks();
            if (oracle.size() != fast.vertices().size()) {
                throw new AssertionError("benchmark meshes diverged for " + name);
            }
        }
        return new Sample(name, oracleNs, fastNs, vertices, fastBlocks);
    }

    public static String format(final Sample sample) {
        return String.format(
                Locale.ROOT,
                "%s: oracle=%.3fms fast=%.3fms ratio=%.2fx vertices=%d fastBlocks=%d | CPU meshing time only, NOT a real FPS/GPU claim, requires hardware A/B before any performance statement",
                sample.name(),
                sample.oracleNs() / 1_000_000.0,
                sample.fastPathNs() / 1_000_000.0,
                sample.ratio(),
                sample.vertices(),
                sample.fastPathBlocks());
    }
}
