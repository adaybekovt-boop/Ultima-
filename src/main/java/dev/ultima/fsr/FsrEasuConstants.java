package dev.ultima.fsr;

/**
 * CPU-side {@code FsrEasuCon} / {@code FsrRcasCon} from AMD FidelityFX FSR 1.0.2
 * ({@code ffx_fsr1.h} v1.20210629, commit {@code a21ffb8f6c13233ba336352bdff293894c706575}).
 *
 * <p>The official header stores each float as a {@code uint} bit pattern
 * ({@code AU1_AF1}). Ultima uploads the same values as {@code vec4} UBO floats,
 * so this class keeps both the float words and the official uint encodings.
 *
 * <p>Copyright (c) 2021 Advanced Micro Devices, Inc. MIT license. This is a
 * Java transcription of the constant-setup functions, not a copy of any
 * Minecraft-mod integration.
 */
public final class FsrEasuConstants {
    public static final int VEC4_COUNT = 4;

    private FsrEasuConstants() {
    }

    /**
     * Result of {@code FsrEasuCon}. {@code con0..con3} are the four official
     * constant words as floats; {@code conBits} are {@code floatToIntBits} of
     * those words (AMD {@code AU1_AF1}).
     */
    public record EasuCon(float[] con0, float[] con1, float[] con2, float[] con3, int[][] conBits) {
        public EasuCon {
            con0 = copy4(con0);
            con1 = copy4(con1);
            con2 = copy4(con2);
            con3 = copy4(con3);
            conBits = new int[][] {copyBits(con0), copyBits(con1), copyBits(con2), copyBits(con3)};
        }

        public float con(final int vector, final int component) {
            return switch (vector) {
                case 0 -> this.con0[component];
                case 1 -> this.con1[component];
                case 2 -> this.con2[component];
                case 3 -> this.con3[component];
                default -> throw new IllegalArgumentException("con vector " + vector);
            };
        }
    }

    public record RcasCon(float sharpnessLinear, int sharpnessBits) {
    }

    /**
     * Official {@code FsrEasuCon}. When the input image is the whole viewport
     * (Ultima's case), {@code inputViewport} equals {@code inputSize}.
     */
    public static EasuCon easu(
            final float inputViewportX,
            final float inputViewportY,
            final float inputSizeX,
            final float inputSizeY,
            final float outputSizeX,
            final float outputSizeY) {
        float[] con0 = new float[4];
        float[] con1 = new float[4];
        float[] con2 = new float[4];
        float[] con3 = new float[4];
        // Output integer position to a pixel position in viewport.
        con0[0] = inputViewportX * rcp(outputSizeX);
        con0[1] = inputViewportY * rcp(outputSizeY);
        con0[2] = 0.5F * inputViewportX * rcp(outputSizeX) - 0.5F;
        con0[3] = 0.5F * inputViewportY * rcp(outputSizeY) - 0.5F;
        // Viewport pixel position to normalized image space (upper-left of 'F').
        con1[0] = rcp(inputSizeX);
        con1[1] = rcp(inputSizeY);
        con1[2] = 1.0F * rcp(inputSizeX);
        con1[3] = -1.0F * rcp(inputSizeY);
        // Offsets from gather center (0) instead of 'F'.
        con2[0] = -1.0F * rcp(inputSizeX);
        con2[1] = 2.0F * rcp(inputSizeY);
        con2[2] = 1.0F * rcp(inputSizeX);
        con2[3] = 2.0F * rcp(inputSizeY);
        con3[0] = 0.0F * rcp(inputSizeX);
        con3[1] = 4.0F * rcp(inputSizeY);
        con3[2] = 0.0F;
        con3[3] = 0.0F;
        return new EasuCon(con0, con1, con2, con3, null);
    }

    /**
     * Convenience when the rendered viewport fills the input color resource.
     */
    public static EasuCon easuSameViewportAndInput(final int inputWidth, final int inputHeight, final int outputWidth, final int outputHeight) {
        return easu(inputWidth, inputHeight, inputWidth, inputHeight, outputWidth, outputHeight);
    }

    /**
     * Official {@code FsrRcasCon}: {@code sharpness} is in stops
     * ({@code 0} = maximum sharpening). The uploaded linear value is
     * {@code exp2(-sharpness)}.
     */
    public static RcasCon rcas(final float sharpnessStops) {
        float linear = (float)Math.exp(Math.log(2.0) * (double)(-sharpnessStops));
        return new RcasCon(linear, Float.floatToIntBits(linear));
    }

    public static float rcp(final float value) {
        return 1.0F / value;
    }

    private static float[] copy4(final float[] values) {
        if (values == null || values.length != 4) {
            throw new IllegalArgumentException("FSR constant vector must have 4 floats");
        }
        return new float[] {values[0], values[1], values[2], values[3]};
    }

    private static int[] copyBits(final float[] values) {
        return new int[] {
            Float.floatToIntBits(values[0]),
            Float.floatToIntBits(values[1]),
            Float.floatToIntBits(values[2]),
            Float.floatToIntBits(values[3])
        };
    }
}
