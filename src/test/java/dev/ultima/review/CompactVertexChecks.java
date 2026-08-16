package dev.ultima.review;

import dev.ultima.config.UltimaModules;
import dev.ultima.lab.LabFeatureGates;
import dev.ultima.vertex.CompactTerrainMetrics;
import dev.ultima.vertex.CompactTerrainVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

final class CompactVertexChecks {
    private CompactVertexChecks() {
    }

    static void run() {
        testStrideAndDependency();
        testUberAlignPolicy();
        testVanillaOpaqueFailClosed();
        testPositionRoundTrip();
        testExtremeLocalCoordinates();
        testUvAndLight();
        testVanillaConversion();
    }

    private static void testStrideAndDependency() {
        if (CompactTerrainVertex.BYTES != 20) {
            throw new AssertionError("compact stride must be 20");
        }
        if (CompactTerrainVertex.VANILLA_BLOCK_STRIDE != 28) {
            throw new AssertionError("vanilla BLOCK stride must stay 28");
        }
        if (CompactTerrainVertex.BYTES >= CompactTerrainVertex.VANILLA_BLOCK_STRIDE) {
            throw new AssertionError("compact format must be smaller than vanilla BLOCK");
        }
        UltimaModules.Module compact = LabFeatureGates.require(LabFeatureGates.COMPACT_TERRAIN_VERTICES);
        if (!compact.dependencies().equals(List.of(LabFeatureGates.RETAINED_TERRAIN))) {
            throw new AssertionError(
                    "GPU compact vertices require retained_terrain so vanilla translucent/fail-open keep BLOCK stride, got "
                            + compact.dependencies());
        }
        UltimaModules.Module mesher = LabFeatureGates.require(LabFeatureGates.DATA_MESHER);
        if (compact.dependencies().contains(LabFeatureGates.DATA_MESHER) || !mesher.dependencies().isEmpty()) {
            throw new AssertionError("compact vertices must not require data_mesher");
        }
    }

    private static void testUberAlignPolicy() {
        if (CompactTerrainVertex.uberAlignSize("solid", 28) != CompactTerrainVertex.BYTES
                || CompactTerrainVertex.uberAlignSize("cutout", 28) != CompactTerrainVertex.BYTES) {
            throw new AssertionError("opaque vertex heaps must use compact stride");
        }
        if (CompactTerrainVertex.uberAlignSize("translucent", 28) != 28) {
            throw new AssertionError("translucent vertex heaps must stay vanilla BLOCK");
        }
        if (CompactTerrainVertex.uberAlignSize("solid", 8) != 8
                || CompactTerrainVertex.uberAlignSize("cutout", 8) != 8
                || CompactTerrainVertex.uberAlignSize("translucent", 8) != 8) {
            throw new AssertionError("index heaps must stay 8-byte aligned");
        }
    }

    private static void testVanillaOpaqueFailClosed() {
        if (CompactTerrainVertex.vanillaOpaqueDrawUnsafe(false)) {
            throw new AssertionError("vanilla opaque is safe when compact GPU path is off");
        }
        if (!CompactTerrainVertex.vanillaOpaqueDrawUnsafe(true)) {
            throw new AssertionError("vanilla opaque must be suppressed when compact GPU path is on");
        }
    }

    private static void testPositionRoundTrip() {
        float[] samples = {
                0.0F, 1.0F, 8.0F, 15.0F, 16.0F, -1.0F, 17.5F, 0.5F, 1.0F / 16.0F, -31.5F, 31.0F
        };
        for (float sample : samples) {
            if (!CompactTerrainVertex.positionInRange(sample)) {
                throw new AssertionError("sample should be in range: " + sample);
            }
            int packed = CompactTerrainVertex.encodePosition(sample);
            float decoded = CompactTerrainVertex.decodePosition(packed);
            float error = Math.abs(decoded - sample);
            if (error > CompactTerrainVertex.MAX_POSITION_ERROR + 1.0e-6F) {
                throw new AssertionError("position error " + error + " for " + sample + " decoded=" + decoded);
            }
        }
        if (CompactTerrainVertex.positionInRange(-32.001F) || CompactTerrainVertex.positionInRange(32.0F)) {
            throw new AssertionError("range edges");
        }
        if (CompactTerrainVertex.encodePosition(-100.0F) != 0 || CompactTerrainVertex.encodePosition(100.0F) != 65535) {
            throw new AssertionError("OOR positions must saturate the packed range");
        }
    }

    private static void testExtremeLocalCoordinates() {
        float[][] corners = {
                {0.0F, 0.0F, 0.0F},
                {16.0F, 16.0F, 16.0F},
                {-2.0F, -1.0F, 18.0F},
                {15.999F, 0.001F, 8.0F},
        };
        ByteBuffer compact = ByteBuffer.allocate(CompactTerrainVertex.BYTES * corners.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] corner : corners) {
            CompactTerrainVertex.write(compact, corner[0], corner[1], corner[2], 0xFFFFFFFF, 0.25F, 0.75F, (short)240, (short)0);
        }
        compact.flip();
        for (int i = 0; i < corners.length; i++) {
            CompactTerrainVertex.Decoded decoded = CompactTerrainVertex.read(compact, i);
            for (int axis = 0; axis < 3; axis++) {
                float expected = axis == 0 ? corners[i][0] : axis == 1 ? corners[i][1] : corners[i][2];
                float actual = axis == 0 ? decoded.x() : axis == 1 ? decoded.y() : decoded.z();
                if (Math.abs(actual - expected) > CompactTerrainVertex.MAX_POSITION_ERROR + 1.0e-6F) {
                    throw new AssertionError("corner reconstruction axis=" + axis + " expected=" + expected + " actual=" + actual);
                }
            }
            if (decoded.color() != 0xFFFFFFFF || decoded.lightU() != 240 || decoded.lightV() != 0) {
                throw new AssertionError("color/light must be bit-identical");
            }
        }
    }

    private static void testUvAndLight() {
        float[] uvs = {0.0F, 1.0F, 0.5F, 16.0F / 4096.0F, 4095.0F / 4096.0F};
        for (float uv : uvs) {
            int packed = CompactTerrainVertex.encodeUv(uv);
            float decoded = CompactTerrainVertex.decodeUv(packed);
            if (Math.abs(decoded - uv) > 1.0F / 65535.0F + 1.0e-7F) {
                throw new AssertionError("UV error for " + uv + " decoded=" + decoded);
            }
        }
        if (CompactTerrainVertex.encodeUv(-0.25F) != 0 || CompactTerrainVertex.encodeUv(2.0F) != 65535) {
            throw new AssertionError("UV saturation");
        }
    }

    private static void testVanillaConversion() {
        CompactTerrainMetrics.reset();
        ByteBuffer vanilla = ByteBuffer.allocate(CompactTerrainVertex.VANILLA_BLOCK_STRIDE * 2).order(ByteOrder.LITTLE_ENDIAN);
        vanilla.putFloat(1.25F);
        vanilla.putFloat(2.5F);
        vanilla.putFloat(3.75F);
        vanilla.putInt(0x11223344);
        vanilla.putFloat(0.125F);
        vanilla.putFloat(0.875F);
        vanilla.putShort((short)15);
        vanilla.putShort((short)240);
        vanilla.putFloat(0.0F);
        vanilla.putFloat(16.0F);
        vanilla.putFloat(-1.0F);
        vanilla.putInt(0xAABBCCDD);
        vanilla.putFloat(0.0F);
        vanilla.putFloat(1.0F);
        vanilla.putShort((short)0);
        vanilla.putShort((short)0);
        vanilla.flip();
        ByteBuffer compact = CompactTerrainVertex.convertVanillaBlock(vanilla);
        if (compact.remaining() != CompactTerrainVertex.BYTES * 2) {
            throw new AssertionError("converted byte count");
        }
        CompactTerrainVertex.Decoded first = CompactTerrainVertex.read(compact, 0);
        if (Math.abs(first.x() - 1.25F) > CompactTerrainVertex.MAX_POSITION_ERROR
                || Math.abs(first.y() - 2.5F) > CompactTerrainVertex.MAX_POSITION_ERROR
                || Math.abs(first.z() - 3.75F) > CompactTerrainVertex.MAX_POSITION_ERROR) {
            throw new AssertionError("converted position " + first);
        }
        if (first.color() != 0x11223344 || first.lightU() != 15 || first.lightV() != 240) {
            throw new AssertionError("converted color/light");
        }
        if (Math.abs(first.u() - 0.125F) > 1.0F / 65535.0F + 1.0e-6F || Math.abs(first.v() - 0.875F) > 1.0F / 65535.0F + 1.0e-6F) {
            throw new AssertionError("converted uv " + first.u() + "," + first.v());
        }
        CompactTerrainMetrics.Snapshot snapshot = CompactTerrainMetrics.snapshot();
        if (snapshot.vertexCount() != 2L || snapshot.vertexBytesPerVertex() != 20 || snapshot.vanillaBytesPerVertex() != 28) {
            throw new AssertionError("compact metrics");
        }
        if (CompactTerrainVertex.convertVanillaBlock(ByteBuffer.allocate(10)) != null) {
            throw new AssertionError("partial vanilla buffer must be rejected");
        }
    }
}
