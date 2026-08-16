package dev.ultima.meshing;

import java.util.ArrayList;
import java.util.List;

/**
 * Packed-volume visit plan. Same air skip and neighbor reads as the oracle,
 * using linear indices instead of 3D arrays / {@code BlockPos} iteration.
 */
public final class PackedVisitScanner {
    private PackedVisitScanner() {
    }

    public static List<MeshVisit> walk(final PackedSectionVolume volume) {
        List<MeshVisit> visits = new ArrayList<>();
        int originX = volume.originX();
        int originY = volume.originY();
        int originZ = volume.originZ();
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    int packed = SectionIndex.interior(x, y, z);
                    int flags = volume.flags(packed);
                    if (BlockRenderFlags.air(flags)) {
                        continue;
                    }
                    int worldX = originX + x;
                    int worldY = originY + y;
                    int worldZ = originZ + z;
                    visits.add(new MeshVisit(
                            x,
                            y,
                            z,
                            worldX,
                            worldY,
                            worldZ,
                            volume.state(packed),
                            VanillaBlockSeed.defaultSeed(worldX, worldY, worldZ),
                            flags,
                            volume.neighborState(x, y, z, -1, 0, 0),
                            volume.neighborState(x, y, z, 1, 0, 0),
                            volume.neighborState(x, y, z, 0, -1, 0),
                            volume.neighborState(x, y, z, 0, 1, 0),
                            volume.neighborState(x, y, z, 0, 0, -1),
                            volume.neighborState(x, y, z, 0, 0, 1),
                            volume.blockEntitySlot(packed)));
                }
            }
        }
        return visits;
    }
}
