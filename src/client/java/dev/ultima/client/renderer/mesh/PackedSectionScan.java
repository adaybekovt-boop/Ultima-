package dev.ultima.client.renderer.mesh;

/**
 * Visit order for a 16³ section. Matches {@code BlockPos.betweenClosed} for a
 * 16×16×16 cube: x fastest, then y, then z.
 */
public final class PackedSectionScan {
    private PackedSectionScan() {
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(int worldX, int worldY, int worldZ, int localX, int localY, int localZ);
    }

    public static void visit(final int originX, final int originY, final int originZ, final Visitor visitor) {
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    visitor.accept(originX + x, originY + y, originZ + z, x, y, z);
                }
            }
        }
    }
}
