package dev.ultima.meshing;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

/**
 * Common-case cube mesher: one occlusion mask per cell, then blit precomputed
 * face templates. No per-block allocations.
 */
public final class FastPathCubeMesher {
    private static final Direction[] DIRECTIONS = Direction.values();

    public record MeshResult(List<MeshEquivalence.TerrainVertex> vertices, int fastPathBlocks, int fallbackBlocks) {
    }

    private FastPathCubeMesher() {
    }

    public static MeshResult mesh(
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final boolean ambientOcclusion,
            final CardinalLighting lighting) {
        List<MeshEquivalence.TerrainVertex> out = CubeFaceEmitter.newBuffer();
        int fast = 0;
        int fallback = 0;
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    int packed = SectionIndex.interior(x, y, z);
                    int flags = volume.flags(packed);
                    if (BlockRenderFlags.air(flags)) {
                        continue;
                    }
                    if (tessellateCell(volume, lights, x, y, z, ambientOcclusion, lighting, out)) {
                        fast++;
                    } else if (!BlockRenderFlags.air(flags)) {
                        fallback++;
                    }
                }
            }
        }
        return new MeshResult(out, fast, fallback);
    }

    public static boolean tessellateCell(
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final int x,
            final int y,
            final int z,
            final boolean ambientOcclusion,
            final CardinalLighting lighting,
            final List<MeshEquivalence.TerrainVertex> out) {
        int packed = SectionIndex.interior(x, y, z);
        int flags = volume.flags(packed);
        int stateId = volume.state(packed);
        if (BlockRenderFlags.air(flags) || !SectionFixtures.fixtureAllowsFastPath(stateId)) {
            return false;
        }
        if (BlockRenderFlags.skipRendering(flags) || BlockRenderFlags.hasFluid(flags)) {
            return false;
        }
        int mask = OcclusionMask.visibleFaces(volume, x, y, z);
        if (mask == OcclusionMask.COMPLEX) {
            return false;
        }
        FullCubeTemplates.CubeMaterial material = FullCubeTemplates.materialOf(stateId);
        boolean useAo = ambientOcclusion && stateId != SectionFixtures.LIGHT;
        for (Direction direction : DIRECTIONS) {
            if (OcclusionMask.visible(mask, direction)) {
                CubeFaceEmitter.emit(out, volume, lights, x, y, z, direction, material, useAo, lighting);
            }
        }
        return true;
    }
}
