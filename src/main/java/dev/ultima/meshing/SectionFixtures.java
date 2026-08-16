package dev.ultima.meshing;

/**
 * Synthetic palette used by mesh-equivalence tests. These IDs are fixtures, not
 * Minecraft registry IDs. Flags are the documented properties vanilla would
 * observe for that kind of cell.
 */
public final class SectionFixtures {
    public static final int AIR = PackedSectionVolume.AIR_ID;
    public static final int FULL_CUBE = 1;
    public static final int CUTOUT = 2;
    public static final int LEAVES = 3;
    public static final int PLANT = 4;
    public static final int STAIRS = 5;
    public static final int SLAB = 6;
    public static final int FENCE = 7;
    public static final int FLUID = 8;
    public static final int TRANSPARENT = 9;
    public static final int TINT = 10;
    public static final int LIGHT = 11;
    public static final int BLOCK_ENTITY = 12;
    public static final int RANDOM = 13;

    private SectionFixtures() {
    }

    public static byte flags(final int stateId) {
        return switch (stateId) {
            case AIR -> BlockRenderFlags.pack(true, false, false, false, false);
            case FULL_CUBE, LIGHT, TINT -> BlockRenderFlags.pack(false, true, false, false, true);
            case CUTOUT, LEAVES, PLANT, STAIRS, SLAB, FENCE, TRANSPARENT, RANDOM ->
                    BlockRenderFlags.pack(false, false, false, false, true);
            case FLUID -> BlockRenderFlags.pack(false, false, false, true, false);
            case BLOCK_ENTITY -> BlockRenderFlags.pack(false, true, true, false, true);
            default -> throw new IllegalArgumentException("unknown fixture state " + stateId);
        };
    }

    public static int[][][] emptyHalo() {
        int[][][] states = new int[SectionIndex.EXTENT][SectionIndex.EXTENT][SectionIndex.EXTENT];
        return states;
    }

    public static byte[][][] flagsOf(final int[][][] states) {
        byte[][][] flags = new byte[SectionIndex.EXTENT][SectionIndex.EXTENT][SectionIndex.EXTENT];
        for (int x = 0; x < SectionIndex.EXTENT; x++) {
            for (int y = 0; y < SectionIndex.EXTENT; y++) {
                for (int z = 0; z < SectionIndex.EXTENT; z++) {
                    flags[x][y][z] = flags(states[x][y][z]);
                }
            }
        }
        return flags;
    }

    public static PackedSectionVolume pack(
            final int originX,
            final int originY,
            final int originZ,
            final int[][][] states) {
        PackedSectionVolume volume = new PackedSectionVolume();
        volume.begin(originX, originY, originZ);
        for (int z = 0; z < SectionIndex.EXTENT; z++) {
            for (int y = 0; y < SectionIndex.EXTENT; y++) {
                for (int x = 0; x < SectionIndex.EXTENT; x++) {
                    int id = states[x][y][z];
                    int packed = SectionIndex.packed(x, y, z);
                    volume.setCell(packed, id, flags(id));
                    if (BlockRenderFlags.hasBlockEntity(flags(id)) && SectionIndex.inInterior(x - 1, y - 1, z - 1)) {
                        volume.addBlockEntity(packed);
                    }
                }
            }
        }
        volume.freeze();
        return volume;
    }

    public static int[][][] entitySlots(final int[][][] states) {
        int[][][] slots = new int[SectionIndex.EXTENT][SectionIndex.EXTENT][SectionIndex.EXTENT];
        int next = 0;
        for (int z = 0; z < SectionIndex.EXTENT; z++) {
            for (int y = 0; y < SectionIndex.EXTENT; y++) {
                for (int x = 0; x < SectionIndex.EXTENT; x++) {
                    slots[x][y][z] = -1;
                    if (BlockRenderFlags.hasBlockEntity(flags(states[x][y][z]))
                            && SectionIndex.inInterior(x - 1, y - 1, z - 1)) {
                        slots[x][y][z] = next++;
                    }
                }
            }
        }
        return slots;
    }

    public static void setInterior(final int[][][] states, final int x, final int y, final int z, final int id) {
        states[x + SectionIndex.HALO][y + SectionIndex.HALO][z + SectionIndex.HALO] = id;
    }

    public static void setHalo(final int[][][] states, final int localX, final int localY, final int localZ, final int id) {
        states[localX][localY][localZ] = id;
    }
}
