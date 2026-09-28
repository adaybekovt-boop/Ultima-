package dev.ultima.meshing;

import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.CardinalLighting;

/**
 * Cubic, non-partial AO for unit-cube faces. Matches vanilla 26.2
 * {@code BlockModelLighter.prepareQuadAmbientOcclusion} when
 * {@code faceCubic && !facePartial} (the full-cube common case).
 *
 * Sampling pattern is from vanilla {@code AdjacencyInfo} / {@code AmbientVertexRemap}.
 * Independent of Sodium/Lithium source.
 */
public final class CubeAmbientOcclusion {
    private static final Direction[][] CORNERS = new Direction[6][4];
    private static final int[][] REMAP = new int[6][4];
    private static final float OPAQUE_BRIGHTNESS = 0.2F;
    private static final float TRANSLUCENT_BRIGHTNESS = 1.0F;

    static {
        CORNERS[Direction.DOWN.ordinal()] = new Direction[]{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
        CORNERS[Direction.UP.ordinal()] = new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH};
        CORNERS[Direction.NORTH.ordinal()] = new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST};
        CORNERS[Direction.SOUTH.ordinal()] = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP};
        CORNERS[Direction.WEST.ordinal()] = new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
        CORNERS[Direction.EAST.ordinal()] = new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
        REMAP[Direction.DOWN.ordinal()] = new int[]{0, 1, 2, 3};
        REMAP[Direction.UP.ordinal()] = new int[]{2, 3, 0, 1};
        REMAP[Direction.NORTH.ordinal()] = new int[]{3, 0, 1, 2};
        REMAP[Direction.SOUTH.ordinal()] = new int[]{0, 1, 2, 3};
        REMAP[Direction.WEST.ordinal()] = new int[]{3, 0, 1, 2};
        REMAP[Direction.EAST.ordinal()] = new int[]{1, 2, 3, 0};
    }

    public record VertexShade(int[] color, int[] light) {
    }

    private CubeAmbientOcclusion() {
    }

    public static VertexShade shadeFace(
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final int x,
            final int y,
            final int z,
            final Direction direction,
            final boolean ambientOcclusion,
            final boolean applyShade,
            final CardinalLighting lighting,
            final int tintColor) {
        int[] colors = new int[4];
        int[] packedLights = new int[4];
        float directional = FullCubeTemplates.shade(direction, applyShade, lighting);
        if (!ambientOcclusion) {
            int light = lights.get(SectionIndex.neighbor(x, y, z, direction.getStepX(), direction.getStepY(), direction.getStepZ()));
            int color = ARGB.scaleRGB(ARGB.gray(directional), 1.0F);
            color = tint(color, tintColor);
            for (int i = 0; i < 4; i++) {
                colors[i] = color;
                packedLights[i] = light;
            }
            return new VertexShade(colors, packedLights);
        }

        int bx = x + direction.getStepX();
        int by = y + direction.getStepY();
        int bz = z + direction.getStepZ();
        Direction[] corners = CORNERS[direction.ordinal()];
        int[] light = new int[4];
        float[] shade = new float[4];
        boolean[] translucent = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int cx = bx + corners[i].getStepX();
            int cy = by + corners[i].getStepY();
            int cz = bz + corners[i].getStepZ();
            int packed = packedInteriorHalo(cx, cy, cz);
            light[i] = lights.get(packed);
            shade[i] = brightness(volume.flags(packed));
            int cornerPacked = packedInteriorHalo(cx + direction.getStepX(), cy + direction.getStepY(), cz + direction.getStepZ());
            translucent[i] = !BlockRenderFlags.solidRender(volume.flags(cornerPacked));
        }

        float[] cornerShade = new float[4];
        int[] cornerLight = new int[4];
        sampleDiagonal(volume, lights, bx, by, bz, corners[0], corners[2], translucent[2], translucent[0], shade[0], light[0], 0, cornerShade, cornerLight);
        sampleDiagonal(volume, lights, bx, by, bz, corners[0], corners[3], translucent[3], translucent[0], shade[0], light[0], 1, cornerShade, cornerLight);
        sampleDiagonal(volume, lights, bx, by, bz, corners[1], corners[2], translucent[2], translucent[1], shade[0], light[0], 2, cornerShade, cornerLight);
        sampleDiagonal(volume, lights, bx, by, bz, corners[1], corners[3], translucent[3], translucent[1], shade[0], light[0], 3, cornerShade, cornerLight);

        int lightCenter = lights.get(SectionIndex.interior(x, y, z));
        int nextPacked = packedInteriorHalo(bx, by, bz);
        if (!BlockRenderFlags.solidRender(volume.flags(nextPacked))) {
            lightCenter = lights.get(nextPacked);
        }
        float shadeCenter = brightness(volume.flags(nextPacked));

        float level1 = (shade[3] + shade[0] + cornerShade[1] + shadeCenter) * 0.25F;
        float level2 = (shade[2] + shade[0] + cornerShade[0] + shadeCenter) * 0.25F;
        float level3 = (shade[2] + shade[1] + cornerShade[2] + shadeCenter) * 0.25F;
        float level4 = (shade[3] + shade[1] + cornerShade[3] + shadeCenter) * 0.25F;
        int[] remap = REMAP[direction.ordinal()];
        int[] blended = {
                LightCoordsUtil.smoothBlend(light[3], light[0], cornerLight[1], lightCenter),
                LightCoordsUtil.smoothBlend(light[2], light[0], cornerLight[0], lightCenter),
                LightCoordsUtil.smoothBlend(light[2], light[1], cornerLight[2], lightCenter),
                LightCoordsUtil.smoothBlend(light[3], light[1], cornerLight[3], lightCenter)
        };
        float[] levels = {level1, level2, level3, level4};
        for (int i = 0; i < 4; i++) {
            int color = ARGB.scaleRGB(ARGB.gray(levels[i]), directional);
            packedLights[remap[i]] = blended[i];
            colors[remap[i]] = tint(color, tintColor);
        }
        return new VertexShade(colors, packedLights);
    }

    private static void sampleDiagonal(
            final PackedSectionVolume volume,
            final PackedLightVolume lights,
            final int bx,
            final int by,
            final int bz,
            final Direction a,
            final Direction b,
            final boolean translucentB,
            final boolean translucentA,
            final float shade0,
            final int light0,
            final int slot,
            final float[] cornerShade,
            final int[] cornerLight) {
        if (!translucentB && !translucentA) {
            // Vanilla uses shade0/light0 on this branch, including the (1,2) pair.
            cornerShade[slot] = shade0;
            cornerLight[slot] = light0;
            return;
        }
        int packed = packedInteriorHalo(bx + a.getStepX() + b.getStepX(), by + a.getStepY() + b.getStepY(), bz + a.getStepZ() + b.getStepZ());
        cornerShade[slot] = brightness(volume.flags(packed));
        cornerLight[slot] = lights.get(packed);
    }

    private static float brightness(final int flags) {
        return BlockRenderFlags.solidRender(flags) ? OPAQUE_BRIGHTNESS : TRANSLUCENT_BRIGHTNESS;
    }

    private static int tint(final int color, final int tintColor) {
        return tintColor == -1 ? color : ARGB.multiply(color, tintColor);
    }

    /**
     * Clamp a possibly out-of-interior coordinate into the 18³ halo. Out-of-halo
     * samples (rare in tests) read the nearest halo cell, matching a snapshot
     * that falls back to air outside the captured region in unit tests.
     */
    static int packedInteriorHalo(final int x, final int y, final int z) {
        int lx = Math.min(SectionIndex.EXTENT - 1, Math.max(0, x + SectionIndex.HALO));
        int ly = Math.min(SectionIndex.EXTENT - 1, Math.max(0, y + SectionIndex.HALO));
        int lz = Math.min(SectionIndex.EXTENT - 1, Math.max(0, z + SectionIndex.HALO));
        return SectionIndex.packed(lx, ly, lz);
    }
}
