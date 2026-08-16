package dev.ultima.retained;

/**
 * CPU/GPU contract for the retained opaque {@code ChunkSection} header UBO.
 *
 * <p>Offsets follow std140 as written by {@code Std140Builder}: {@code mat4}
 * model-view, unused fade float, {@code ivec2} atlas size, unused {@code ivec3}
 * origin (section origin lives in the texel table). Size must equal vanilla
 * {@code DynamicUniforms.CHUNK_SECTION_UBO_SIZE}.
 *
 * <p>The unused fade/origin fields are written so the buffer remains a valid
 * vanilla {@code ChunkSection} uniform; retained shaders read origin/fade from
 * {@code UltimaSectionTable}.
 */
public final class RetainedHeaderLayout {
    public static final int MODEL_VIEW_OFFSET = 0;
    public static final int MODEL_VIEW_BYTES = 64;
    public static final int UNUSED_FADE_OFFSET = 64;
    public static final int TEXTURE_SIZE_OFFSET = 72;
    public static final int UNUSED_ORIGIN_OFFSET = 80;

    private RetainedHeaderLayout() {
    }
}
