package dev.ultima.vertex;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Compact-vertex counters for later hardware A/B. Not a performance claim.
 */
public final class CompactTerrainMetrics {
    private static final AtomicLong VERTEX_COUNT = new AtomicLong();
    private static final AtomicLong TERRAIN_VERTEX_BYTES_RESIDENT = new AtomicLong();
    private static final AtomicLong UPLOAD_BYTES = new AtomicLong();
    private static final AtomicLong CLAMPED_POSITIONS = new AtomicLong();
    private static final AtomicLong SATURATED_UVS = new AtomicLong();
    private static final AtomicLong CONVERSIONS = new AtomicLong();

    private CompactTerrainMetrics() {
    }

    public static int vertexBytesPerVertex() {
        return CompactTerrainVertex.BYTES;
    }

    public static int vanillaBytesPerVertex() {
        return CompactTerrainVertex.VANILLA_BLOCK_STRIDE;
    }

    public static void reset() {
        VERTEX_COUNT.set(0L);
        TERRAIN_VERTEX_BYTES_RESIDENT.set(0L);
        UPLOAD_BYTES.set(0L);
        CLAMPED_POSITIONS.set(0L);
        SATURATED_UVS.set(0L);
        CONVERSIONS.set(0L);
    }

    public static void recordConversion(
            final int vertexCount,
            final long uploadBytes,
            final int clampedPositions,
            final int saturatedUvs) {
        VERTEX_COUNT.addAndGet(vertexCount);
        TERRAIN_VERTEX_BYTES_RESIDENT.addAndGet(uploadBytes);
        UPLOAD_BYTES.addAndGet(uploadBytes);
        CLAMPED_POSITIONS.addAndGet(clampedPositions);
        SATURATED_UVS.addAndGet(saturatedUvs);
        CONVERSIONS.incrementAndGet();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                vertexBytesPerVertex(),
                vanillaBytesPerVertex(),
                TERRAIN_VERTEX_BYTES_RESIDENT.get(),
                UPLOAD_BYTES.get(),
                VERTEX_COUNT.get(),
                CLAMPED_POSITIONS.get(),
                SATURATED_UVS.get(),
                CONVERSIONS.get());
    }

    public record Snapshot(
            int vertexBytesPerVertex,
            int vanillaBytesPerVertex,
            long terrainVertexBytesResident,
            long uploadBytes,
            long vertexCount,
            long clampedPositions,
            long saturatedUvs,
            long conversions) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"compactVertexMetrics\": {\n")
                    .append("    \"vertexBytesPerVertex\": ").append(this.vertexBytesPerVertex).append(",\n")
                    .append("    \"vanillaBytesPerVertex\": ").append(this.vanillaBytesPerVertex).append(",\n")
                    .append("    \"terrainVertexBytesResident\": ").append(this.terrainVertexBytesResident).append(",\n")
                    .append("    \"uploadBytes\": ").append(this.uploadBytes).append(",\n")
                    .append("    \"vertexCount\": ").append(this.vertexCount).append(",\n")
                    .append("    \"clampedPositions\": ").append(this.clampedPositions).append(",\n")
                    .append("    \"saturatedUvs\": ").append(this.saturatedUvs).append(",\n")
                    .append("    \"conversions\": ").append(this.conversions).append("\n")
                    .append("  }");
        }
    }
}
