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
        int stateId = volume.state(SectionIndex.interior(x, y, z));
        if (!FastPathCriteria.fromFixtureState(stateId).fastPath()) {
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
