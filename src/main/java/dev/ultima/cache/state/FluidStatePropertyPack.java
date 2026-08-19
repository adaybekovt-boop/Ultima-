package dev.ultima.cache.state;

import org.jspecify.annotations.Nullable;

/**
 * Packed per-{@code FluidState} memo word.
 *
 * <pre>
 * bits 0-1   kind: 0 unset, 1 skip, 2 vanilla
 * bit  2     source present
 * bit  3     isSource
 * bit  4     empty present
 * bit  5     isEmpty
 * bit  6     amount present
 * bits 7-11  amount (0-16)
 * bit  12    height present (see parallel float table)
 * </pre>
 */
public final class FluidStatePropertyPack {
    public static final int KIND_UNSET = 0;
    public static final int KIND_SKIP = 1;
    public static final int KIND_VANILLA = 2;

    private static final int KIND_MASK = 0b11;
    private static final int SOURCE_PRESENT = 1 << 2;
    private static final int SOURCE_VALUE = 1 << 3;
    private static final int EMPTY_PRESENT = 1 << 4;
    private static final int EMPTY_VALUE = 1 << 5;
    private static final int AMOUNT_PRESENT = 1 << 6;
    private static final int AMOUNT_SHIFT = 7;
    private static final int AMOUNT_MASK = 0x1F;
    private static final int HEIGHT_PRESENT = 1 << 12;

    private FluidStatePropertyPack() {
    }

    public static int kind(final int packed) {
        return packed & KIND_MASK;
    }

    public static int withKind(final int packed, final int kind) {
        return (packed & ~KIND_MASK) | (kind & KIND_MASK);
    }

    public static boolean isSkip(final int packed) {
        return kind(packed) == KIND_SKIP;
    }

    public static boolean isVanilla(final int packed) {
        return kind(packed) == KIND_VANILLA;
    }

    public static @Nullable Boolean isSource(final int packed) {
        return (packed & SOURCE_PRESENT) == 0 ? null : (packed & SOURCE_VALUE) != 0;
    }

    public static int withSource(final int packed, final boolean value) {
        int next = packed | SOURCE_PRESENT;
        return value ? next | SOURCE_VALUE : next & ~SOURCE_VALUE;
    }

    public static @Nullable Boolean isEmpty(final int packed) {
        return (packed & EMPTY_PRESENT) == 0 ? null : (packed & EMPTY_VALUE) != 0;
    }

    public static int withEmpty(final int packed, final boolean value) {
        int next = packed | EMPTY_PRESENT;
        return value ? next | EMPTY_VALUE : next & ~EMPTY_VALUE;
    }

    public static @Nullable Integer amount(final int packed) {
        if ((packed & AMOUNT_PRESENT) == 0) {
            return null;
        }
        return (packed >>> AMOUNT_SHIFT) & AMOUNT_MASK;
    }

    public static int withAmount(final int packed, final int amount) {
        int clamped = Math.max(0, Math.min(AMOUNT_MASK, amount));
        int cleared = packed & ~(AMOUNT_MASK << AMOUNT_SHIFT);
        return cleared | AMOUNT_PRESENT | (clamped << AMOUNT_SHIFT);
    }

    public static boolean heightPresent(final int packed) {
        return (packed & HEIGHT_PRESENT) != 0;
    }

    public static int withHeightPresent(final int packed) {
        return packed | HEIGHT_PRESENT;
    }
}
