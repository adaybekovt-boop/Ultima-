package dev.ultima.meshing;

/**
 * Bit-identical to vanilla {@code Mth.getSeed(x, y, z)} / default
 * {@code BlockBehaviour.getSeed}. Production meshing still calls
 * {@code BlockState.getSeed(pos)} so beds, doors, and double plants keep their
 * special seeds.
 */
public final class VanillaBlockSeed {
    private VanillaBlockSeed() {
    }

    public static long defaultSeed(final int x, final int y, final int z) {
        long seed = x * 3129871 ^ z * 116129781L ^ y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }
}
