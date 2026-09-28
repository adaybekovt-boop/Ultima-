package dev.ultima.meshing;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

/**
 * Shared unit-cube face emitter used by both the vanilla-style oracle and the
 * template fast path. Positions/UVs come from {@link FullCubeTemplates}.
 */
public final class CubeFaceEmitter {
    private CubeFaceEmitter() {
    }

    public static void emit(
            final List<MeshEquivalence.TerrainVertex> out,
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final int x,
            final int y,
            final int z,
            final Direction direction,
            final FullCubeTemplates.CubeMaterial material,
            final boolean ambientOcclusion,
            final CardinalLighting lighting) {
        CubeAmbientOcclusion.VertexShade shade = CubeAmbientOcclusion.shadeFace(
                volume, lights, x, y, z, direction, ambientOcclusion, material.shade(), lighting, material.tintColor());
        float ox = x;
        float oy = y;
        float oz = z;
        for (int vertex = 0; vertex < FullCubeTemplates.VERTICES_PER_FACE; vertex++) {
            out.add(new MeshEquivalence.TerrainVertex(
                    material.layer(),
                    ox + FullCubeTemplates.x(direction, vertex),
                    oy + FullCubeTemplates.y(direction, vertex),
                    oz + FullCubeTemplates.z(direction, vertex),
                    shade.color()[vertex],
                    FullCubeTemplates.u(direction, vertex, material),
                    FullCubeTemplates.v(direction, vertex, material),
                    shade.light()[vertex]));
        }
    }

    public static List<MeshEquivalence.TerrainVertex> newBuffer() {
        return new ArrayList<>();
    }
}
