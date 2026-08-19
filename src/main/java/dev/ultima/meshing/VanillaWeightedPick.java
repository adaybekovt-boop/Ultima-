package dev.ultima.meshing;

import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;

/**
 * Same pick as vanilla {@code WeightedVariants.collectParts}:
 * {@code random.setSeed(seed)} then {@code list.getRandomOrThrow(random)}.
 *
 * <p>{@code ModelBlockRenderer.tesselateBlock} seeds a
 * {@code SingleThreadedRandomSource} with {@code BlockState.getSeed(pos)}
 * before {@code collectParts}. The production cube cache must use that same
 * RNG family and this exact consume sequence so weighted cube UVs stay
 * bit-identical to vanilla.
 */
public final class VanillaWeightedPick {
    private VanillaWeightedPick() {
    }

    public static RandomSource mesherRandom() {
        return RandomSource.createThreadLocalInstance(0L);
    }

    public static <E> E pick(final WeightedList<E> list, final RandomSource random, final long seed) {
        random.setSeed(seed);
        return list.getRandomOrThrow(random);
    }
}
