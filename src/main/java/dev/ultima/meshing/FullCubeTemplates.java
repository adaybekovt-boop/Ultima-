package dev.ultima.meshing;

import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

/**
 * Precomputed unit-cube faces matching vanilla {@code FaceInfo} winding after
 * {@code FaceBakery.recalculateWinding}. Positions are in block-local 0..1.
 *
 * <p>Default UVs match {@code FaceBakery.defaultFaceUV} for a 16³ element with
 * identity rotation and an identity sprite ({@code u,v} in 0..1).
 *
 * Derived from Minecraft 26.2 {@code FaceInfo} / {@code FaceBakery}, not from
 * Sodium or other GPL meshers.
 */
public final class FullCubeTemplates {
    public static final int VERTICES_PER_FACE = 4;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float[][] POS_X = new float[6][4];
    private static final float[][] POS_Y = new float[6][4];
    private static final float[][] POS_Z = new float[6][4];
    private static final float[][] U = new float[6][4];
    private static final float[][] V = new float[6][4];

    static {
        // FaceInfo vertex extents for a 0..1 cube (FaceBakery divides 0..16 by 16).
        put(Direction.DOWN,
                0, 0, 1,
                0, 0, 0,
                1, 0, 0,
                1, 0, 1);
        put(Direction.UP,
                0, 1, 0,
                0, 1, 1,
                1, 1, 1,
                1, 1, 0);
        put(Direction.NORTH,
                1, 1, 0,
                1, 0, 0,
                0, 0, 0,
                0, 1, 0);
        put(Direction.SOUTH,
                0, 1, 1,
                0, 0, 1,
                1, 0, 1,
                1, 1, 1);
        put(Direction.WEST,
                0, 1, 0,
                0, 0, 0,
                0, 0, 1,
                0, 1, 1);
        put(Direction.EAST,
                1, 1, 1,
                1, 0, 1,
                1, 0, 0,
                1, 1, 0);
        for (Direction direction : DIRECTIONS) {
            int face = direction.ordinal();
            // CuboidFace.UVs.getVertexU/V for identity rotation, default 0..16 → 0..1.
            U[face][0] = 0.0F;
            V[face][0] = 0.0F;
            U[face][1] = 0.0F;
            V[face][1] = 1.0F;
            U[face][2] = 1.0F;
            V[face][2] = 1.0F;
            U[face][3] = 1.0F;
            V[face][3] = 0.0F;
        }
    }

    private FullCubeTemplates() {
    }

    public static Direction[] directions() {
        return DIRECTIONS;
    }

    public static float x(final Direction direction, final int vertex) {
        return POS_X[direction.ordinal()][vertex];
    }

    public static float y(final Direction direction, final int vertex) {
        return POS_Y[direction.ordinal()][vertex];
    }

    public static float z(final Direction direction, final int vertex) {
        return POS_Z[direction.ordinal()][vertex];
    }

    public static float u(final Direction direction, final int vertex, final CubeMaterial material) {
        return material.u0() + U[direction.ordinal()][vertex] * material.uSpan();
    }

    public static float v(final Direction direction, final int vertex, final CubeMaterial material) {
        return material.v0(direction) + V[direction.ordinal()][vertex] * material.vSpan();
    }

    public static float shade(final Direction direction, final boolean applyShade, final CardinalLighting lighting) {
        return applyShade ? lighting.byFace(direction) : lighting.up();
    }

    public record CubeMaterial(int layer, int tint, int tintColor, boolean shade, float u0, float uSpan, float vSpan, float upV0) {
        public static CubeMaterial stone() {
            return new CubeMaterial(0, -1, -1, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public static CubeMaterial dirt() {
            return new CubeMaterial(0, -1, -1, true, 0.25F, 0.5F, 0.5F, 0.25F);
        }

        public static CubeMaterial grass() {
            return new CubeMaterial(0, 0, 0xFF71A74D, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public static CubeMaterial glowstone() {
            return new CubeMaterial(0, -1, -1, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public static CubeMaterial glass() {
            return new CubeMaterial(1, -1, -1, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public static CubeMaterial animatedMagma() {
            return new CubeMaterial(0, -1, -1, true, 0.125F, 0.125F, 0.125F, 0.125F);
        }

        public static CubeMaterial foliage() {
            return new CubeMaterial(0, 1, 0xFF48B518, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public static CubeMaterial waterTinted() {
            return new CubeMaterial(0, 2, 0xFF3F76E4, true, 0.0F, 1.0F, 1.0F, 0.0F);
        }

        public float v0(final Direction direction) {
            return direction == Direction.UP ? this.upV0 : this.u0;
        }
    }

    public static CubeMaterial materialOf(final int stateId) {
        return switch (stateId) {
            case SectionFixtures.TINT -> CubeMaterial.grass();
            case SectionFixtures.TINT_FOLIAGE -> CubeMaterial.foliage();
            case SectionFixtures.TINT_WATER -> CubeMaterial.waterTinted();
            case SectionFixtures.LIGHT -> CubeMaterial.glowstone();
            case SectionFixtures.TRANSPARENT -> CubeMaterial.glass();
            case SectionFixtures.ANIMATED -> CubeMaterial.animatedMagma();
            case SectionFixtures.FULL_CUBE, SectionFixtures.BLOCK_ENTITY, SectionFixtures.FACING_CUBE ->
                    CubeMaterial.stone();
            case SectionFixtures.WEIGHTED_CUBE -> CubeMaterial.dirt();
            case SectionFixtures.AXIS_CUBE -> CubeMaterial.glowstone();
            default -> CubeMaterial.stone();
        };
    }

    private static void put(
            final Direction direction,
            final float x0, final float y0, final float z0,
            final float x1, final float y1, final float z1,
            final float x2, final float y2, final float z2,
            final float x3, final float y3, final float z3) {
        int face = direction.ordinal();
        POS_X[face][0] = x0;
        POS_Y[face][0] = y0;
        POS_Z[face][0] = z0;
        POS_X[face][1] = x1;
        POS_Y[face][1] = y1;
        POS_Z[face][1] = z1;
        POS_X[face][2] = x2;
        POS_Y[face][2] = y2;
        POS_Z[face][2] = z2;
        POS_X[face][3] = x3;
        POS_Y[face][3] = y3;
        POS_Z[face][3] = z3;
    }
}
