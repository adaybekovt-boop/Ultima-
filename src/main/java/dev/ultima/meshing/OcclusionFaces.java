package dev.ultima.meshing;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-cube face emission from packed neighbor occupancy. This is a snapshot
 * adjacency test, not a greedy mesher and not the production tessellation path.
 *
 * <p>A face is emitted iff the cell is a full cube and the neighbor in that
 * direction is air. Order is x-fastest interior, then +X,+Y,+Z,-X,-Y,-Z.
 */
public final class OcclusionFaces {
    public record Face(int x, int y, int z, int nx, int ny, int nz) {
    }

    private static final int[][] AXES = {
            {1, 0, 0},
            {0, 1, 0},
            {0, 0, 1},
            {-1, 0, 0},
            {0, -1, 0},
            {0, 0, -1},
    };

    private OcclusionFaces() {
    }

    public static List<Face> fromVolume(final PackedSectionVolume volume, final int fullCubeId) {
        List<Face> faces = new ArrayList<>();
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    if (volume.state(SectionIndex.interior(x, y, z)) != fullCubeId) {
                        continue;
                    }
                    for (int[] axis : AXES) {
                        int neighbor = volume.neighborState(x, y, z, axis[0], axis[1], axis[2]);
                        if (neighbor == PackedSectionVolume.AIR_ID) {
                            faces.add(new Face(x, y, z, axis[0], axis[1], axis[2]));
                        }
                    }
                }
            }
        }
        return faces;
    }

    public static List<Face> fromHaloArray(final int[][][] states, final int fullCubeId) {
        List<Face> faces = new ArrayList<>();
        for (int z = 0; z < SectionIndex.INTERIOR; z++) {
            for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                    int lx = x + SectionIndex.HALO;
                    int ly = y + SectionIndex.HALO;
                    int lz = z + SectionIndex.HALO;
                    if (states[lx][ly][lz] != fullCubeId) {
                        continue;
                    }
                    for (int[] axis : AXES) {
                        if (states[lx + axis[0]][ly + axis[1]][lz + axis[2]] == PackedSectionVolume.AIR_ID) {
                            faces.add(new Face(x, y, z, axis[0], axis[1], axis[2]));
                        }
                    }
                }
            }
        }
        return faces;
    }

    public static IntArrayList packedFaceKeys(final List<Face> faces) {
        IntArrayList keys = new IntArrayList(faces.size());
        for (Face face : faces) {
            keys.add(face.x | face.y << 8 | face.z << 16 | (face.nx + 1) << 24 | (face.ny + 1) << 26 | (face.nz + 1) << 28);
        }
        return keys;
    }
}
