package dev.ultima.vertex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ultima compact terrain vertex. Vanilla {@code DefaultVertexFormat.BLOCK} is
 * 28 bytes (float3 pos, rgba8, float2 uv, short2 light) and is not modified.
 *
 * <p>Layout (20 bytes, 4-byte aligned):
 * <pre>
 *  0  RGBA16_UNORM  position.xyz as UNORM16, w unused
 *  8  RGBA8_UNORM   color / tint
 * 12  RG16_UNORM    UV0
 * 16  RG16_SINT     lightmap (bit-identical to vanilla UV2)
 * </pre>
 *
 * Position encoding: {@code packed = round((local + 32) * 1024)}, range
 * {@code [-32, 32)}, unit {@code 1/1024} block, max reconstruction error
 * {@code 1/2048} block. UV is UNORM16 on {@code [0, 1]} with saturation.
 *
 * <p>Minecraft 26.2 terrain {@code BLOCK} has no normal attribute; this format
 * does not invent one. Layer/material stay per-draw. Fog and fade stay in the
 * section table / shader, not in the vertex.
 */
public final class CompactTerrainVertex {
    public static final int VANILLA_BLOCK_STRIDE = 28;
    public static final int BYTES = 20;
    public static final float POS_BIAS = 32.0F;
    public static final float POS_SCALE = 1024.0F;
    public static final float POS_MIN = -POS_BIAS;
    public static final float POS_MAX = POS_BIAS - (1.0F / POS_SCALE);
    public static final float MAX_POSITION_ERROR = 0.5F / POS_SCALE;
    public static final int UV_MASK = 65535;

    private CompactTerrainVertex() {
    }

    public static int encodePosition(final float local) {
        float scaled = (local + POS_BIAS) * POS_SCALE;
        int packed = Math.round(scaled);
        if (packed < 0) {
            return 0;
        }
        if (packed > UV_MASK) {
            return UV_MASK;
        }
        return packed;
    }

    public static float decodePosition(final int packed) {
        return packed / POS_SCALE - POS_BIAS;
    }

    public static boolean positionInRange(final float local) {
        return local >= POS_MIN && local <= POS_MAX;
    }

    public static int encodeUv(final float uv) {
        float clamped = uv < 0.0F ? 0.0F : (uv > 1.0F ? 1.0F : uv);
        return Math.round(clamped * UV_MASK);
    }

    public static float decodeUv(final int packed) {
        return packed / (float)UV_MASK;
    }

    public static void write(
            final ByteBuffer dest,
            final float x,
            final float y,
            final float z,
            final int colorRgba,
            final float u,
            final float v,
            final short lightU,
            final short lightV) {
        dest.putShort((short)encodePosition(x));
        dest.putShort((short)encodePosition(y));
        dest.putShort((short)encodePosition(z));
        dest.putShort((short)0);
        dest.putInt(colorRgba);
        dest.putShort((short)encodeUv(u));
        dest.putShort((short)encodeUv(v));
        dest.putShort(lightU);
        dest.putShort(lightV);
    }

    public static Decoded read(final ByteBuffer src, final int vertexIndex) {
        int base = vertexIndex * BYTES;
        int x = Short.toUnsignedInt(src.getShort(base));
        int y = Short.toUnsignedInt(src.getShort(base + 2));
        int z = Short.toUnsignedInt(src.getShort(base + 4));
        int color = src.getInt(base + 8);
        int u = Short.toUnsignedInt(src.getShort(base + 12));
        int v = Short.toUnsignedInt(src.getShort(base + 14));
        short lightU = src.getShort(base + 16);
        short lightV = src.getShort(base + 18);
        return new Decoded(
                decodePosition(x),
                decodePosition(y),
                decodePosition(z),
                color,
                decodeUv(u),
                decodeUv(v),
                lightU,
                lightV);
    }

    public static VanillaBlock readVanillaBlock(final ByteBuffer src, final int vertexIndex) {
        int base = vertexIndex * VANILLA_BLOCK_STRIDE;
        return new VanillaBlock(
                src.getFloat(base),
                src.getFloat(base + 4),
                src.getFloat(base + 8),
                src.getInt(base + 12),
                src.getFloat(base + 16),
                src.getFloat(base + 20),
                src.getShort(base + 24),
                src.getShort(base + 26));
    }

    /**
     * Converts a vanilla BLOCK vertex buffer to compact vertices. Positions
     * outside {@link #POS_MIN}/{@link #POS_MAX} are clamped. UVs outside
     * {@code [0, 1]} are saturated. Returns {@code null} when the source is not
     * a whole number of vanilla vertices.
     */
    public static ByteBuffer convertVanillaBlock(final ByteBuffer vanilla) {
        if (vanilla == null) {
            return null;
        }
        int bytes = vanilla.remaining();
        if (bytes % VANILLA_BLOCK_STRIDE != 0) {
            return null;
        }
        int vertices = bytes / VANILLA_BLOCK_STRIDE;
        ByteBuffer compact = ByteBuffer.allocateDirect(vertices * BYTES).order(vanilla.order());
        int src = vanilla.position();
        int clampedPositions = 0;
        int saturatedUvs = 0;
        for (int i = 0; i < vertices; i++) {
            int base = src + i * VANILLA_BLOCK_STRIDE;
            float x = vanilla.getFloat(base);
            float y = vanilla.getFloat(base + 4);
            float z = vanilla.getFloat(base + 8);
            if (!positionInRange(x) || !positionInRange(y) || !positionInRange(z)) {
                clampedPositions++;
            }
            float u = vanilla.getFloat(base + 16);
            float v = vanilla.getFloat(base + 20);
            if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
                saturatedUvs++;
            }
            write(
                    compact,
                    x,
                    y,
                    z,
                    vanilla.getInt(base + 12),
                    u,
                    v,
                    vanilla.getShort(base + 24),
                    vanilla.getShort(base + 26));
        }
        compact.flip();
        CompactTerrainMetrics.recordConversion(vertices, vertices * BYTES, clampedPositions, saturatedUvs);
        return compact;
    }

    public record VanillaBlock(float x, float y, float z, int color, float u, float v, short lightU, short lightV) {
    }

    public record Decoded(float x, float y, float z, int color, float u, float v, short lightU, short lightV) {
    }

    public static ByteOrder nativeOrder() {
        return ByteOrder.nativeOrder();
    }

    /**
     * Uber-heap alignment for one {@code ChunkSectionLayer} buffer. Index heaps
     * stay 8. TRANSLUCENT vertex heaps stay on vanilla BLOCK. SOLID/CUTOUT
     * vertex heaps use the compact stride. Matching uses the layer label
     * ({@code solid}/{@code cutout}/{@code translucent}), not heap creation
     * order.
     */
    public static int uberAlignSize(final String layerLabel, final int vanillaAlignSize) {
        if (vanillaAlignSize == 8) {
            return 8;
        }
        if ("translucent".equals(layerLabel)) {
            return vanillaAlignSize;
        }
        return BYTES;
    }

    /**
     * Vanilla SOLID/CUTOUT pipelines fetch {@link #VANILLA_BLOCK_STRIDE}-byte
     * vertices. Compact uber heaps store {@link #BYTES}-byte vertices, so a
     * vanilla opaque draw would mis-fetch. When the compact GPU path is on,
     * retained compact pipelines must submit, or opaque must be skipped.
     */
    public static boolean vanillaOpaqueDrawUnsafe(final boolean compactGpuPathEnabled) {
        return compactGpuPathEnabled;
    }
}
