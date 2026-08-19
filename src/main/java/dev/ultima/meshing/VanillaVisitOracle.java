package dev.ultima.meshing;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent visit-plan oracle: nested x-fastest loops over a 3D halo array,
 * skipping air, matching {@code BlockPos.betweenClosed} then
 * {@code if (!blockState.isAir())}.
 */
public final class VanillaVisitOracle {
    private VanillaVisitOracle() {
    }

    public static List<MeshVisit> walk(
            final int originX,
            final int originY,
            final int originZ,
            final int[][][] states,
            final int[][][] flags,
            final int[][][] entitySlots) {
        requireExtent(states);
        requireExtent(flags);
        List<MeshVisit> visits = new ArrayList<>();
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    int lx = x + SectionIndex.HALO;
                    int ly = y + SectionIndex.HALO;
                    int lz = z + SectionIndex.HALO;
                    int cellFlags = flags[lx][ly][lz];
                    if (BlockRenderFlags.air(cellFlags)) {
                        continue;
                    }
                    int worldX = originX + x;
                    int worldY = originY + y;
                    int worldZ = originZ + z;
                    int entitySlot = entitySlots == null ? -1 : entitySlots[lx][ly][lz];
                    visits.add(new MeshVisit(
                            x,
                            y,
                            z,
                            worldX,
                            worldY,
                            worldZ,
                            states[lx][ly][lz],
                            VanillaBlockSeed.defaultSeed(worldX, worldY, worldZ),
                            cellFlags,
                            states[lx - 1][ly][lz],
                            states[lx + 1][ly][lz],
                            states[lx][ly - 1][lz],
                            states[lx][ly + 1][lz],
                            states[lx][ly][lz - 1],
                            states[lx][ly][lz + 1],
                            entitySlot));
                }
            }
        }
        return visits;
    }

    private static void requireExtent(final int[][][] data) {
        if (data.length != SectionIndex.EXTENT
                || data[0].length != SectionIndex.EXTENT
                || data[0][0].length != SectionIndex.EXTENT) {
            throw new IllegalArgumentException("halo array must be 18³");
        }
    }

}
