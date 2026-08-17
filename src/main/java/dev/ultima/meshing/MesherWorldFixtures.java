package dev.ultima.meshing;

/**
 * Synthetic 16³ interiors that resemble real compile work more than a solid
 * cube or a checkerboard. Still fixtures — not a world dump and not an FPS
 * claim.
 */
public final class MesherWorldFixtures {
    private MesherWorldFixtures() {
    }

    /**
     * Overworld-ish surface column: air above, mixed cubes, plants, a few
     * non-cubes, leaves, and glass (glass must stay fallback).
     */
    public static int[][][] typicalOverworldSurface() {
        int[][][] states = SectionFixtures.emptyHalo();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int height = 4 + ((x * 3 + z * 5) & 3);
                for (int y = 0; y < 16; y++) {
                    int id;
                    if (y > height + 2) {
                        id = SectionFixtures.AIR;
                    } else if (y > height) {
                        id = ((x + z) & 7) == 0 ? SectionFixtures.PLANT : SectionFixtures.AIR;
                    } else if (y == height) {
                        id = switch ((x + z) & 7) {
                            case 0 -> SectionFixtures.TINT;
                            case 1 -> SectionFixtures.LEAVES;
                            case 2 -> SectionFixtures.TRANSPARENT;
                            case 3 -> SectionFixtures.SLAB;
                            default -> SectionFixtures.FULL_CUBE;
                        };
                    } else if (y > height - 3) {
                        id = SectionFixtures.FULL_CUBE;
                    } else {
                        id = ((x + y + z) & 5) == 0 ? SectionFixtures.LIGHT : SectionFixtures.FULL_CUBE;
                    }
                    SectionFixtures.setInterior(states, x, y, z, id);
                }
            }
        }
        return states;
    }

    /**
     * Dense player-built volume: mostly opaque cubes, some stairs/slabs, glass
     * windows, and a few block-entity cells.
     */
    public static int[][][] denseStructure() {
        int[][][] states = SectionFixtures.emptyHalo();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    boolean shell = x == 0 || x == 15 || y == 0 || z == 0 || z == 15;
                    boolean window = shell && y >= 4 && y <= 8 && ((x + z) & 3) == 0;
                    boolean stair = y == 1 && x > 0 && x < 15 && z == 7;
                    int id;
                    if (window) {
                        id = SectionFixtures.TRANSPARENT;
                    } else if (stair) {
                        id = SectionFixtures.STAIRS;
                    } else if (shell) {
                        id = ((x + y) & 1) == 0 ? SectionFixtures.FULL_CUBE : SectionFixtures.TINT;
                    } else if (y == 3 && x == 8 && z == 8) {
                        id = SectionFixtures.BLOCK_ENTITY;
                    } else {
                        id = SectionFixtures.AIR;
                    }
                    SectionFixtures.setInterior(states, x, y, z, id);
                }
            }
        }
        return states;
    }
}
