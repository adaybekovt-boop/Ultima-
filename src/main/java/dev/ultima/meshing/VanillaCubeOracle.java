package dev.ultima.meshing;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

/**
 * Naive per-face tessellator matching vanilla {@code tesselateAmbientOcclusion}
 * / {@code tesselateFlat} control flow for unit cubes: {@code Direction.values()}
 * order, {@code shouldRenderFace} per face, then emit.
 */
public final class VanillaCubeOracle {
    private static final Direction[] DIRECTIONS = Direction.values();

    private VanillaCubeOracle() {
    }

    public static List<MeshEquivalence.TerrainVertex> mesh(
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final boolean ambientOcclusion,
            final CardinalLighting lighting) {
        List<MeshEquivalence.TerrainVertex> out = CubeFaceEmitter.newBuffer();
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    tessellateCell(volume, lights, x, y, z, ambientOcclusion, lighting, out);
                }
            }
        }
        return out;
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
        FullCubeTemplates.CubeMaterial material = FullCubeTemplates.materialOf(stateId);
        boolean useAo = ambientOcclusion && stateId != SectionFixtures.LIGHT;
        for (Direction direction : DIRECTIONS) {
            int neighbor = volume.neighborFlags(x, y, z, direction.getStepX(), direction.getStepY(), direction.getStepZ());
            int face = OcclusionMask.simpleShouldRenderFace(flags, neighbor);
            if (face < 0) {
                return false;
            }
            if (face == 1) {
                CubeFaceEmitter.emit(out, volume, lights, x, y, z, direction, material, useAo, lighting);
            }
        }
        return true;
    }
}
