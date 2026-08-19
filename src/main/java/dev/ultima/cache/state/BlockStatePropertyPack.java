package dev.ultima.cache.state;

import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.Nullable;

/**
 * Packed per-{@code BlockState} memo word. Unknown bits stay unset so a later property can be
 * filled independently. {@link #KIND_SKIP} means the Block class is not vanilla (or is otherwise
 * unproven); every property then falls through to vanilla forever for that id.
 *
 * <pre>
 * bits 0-1   kind: 0 unset, 1 skip, 2 vanilla
 * bit  2     pathType present
 * bits 3-8   pathType ordinal
 * bit  9     signalSource present
 * bit  10    signalSource
 * bit  11    analogPresent
 * bit  12    hasAnalogOutputSignal
 * bit  13    redstoneConductor present
 * bit  14    redstoneConductor
 * bit  15    redstoneConductor skipped (dynamic shape)
 * bit  16    pathfindable LAND present
 * bit  17    pathfindable LAND
 * bit  18    pathfindable WATER present
 * bit  19    pathfindable WATER
 * bit  20    pathfindable AIR present
 * bit  21    pathfindable AIR
 * </pre>
 */
public final class BlockStatePropertyPack {
    public static final int KIND_UNSET = 0;
    public static final int KIND_SKIP = 1;
    public static final int KIND_VANILLA = 2;

    private static final int KIND_MASK = 0b11;
    private static final int PATH_TYPE_PRESENT = 1 << 2;
    private static final int PATH_TYPE_SHIFT = 3;
    private static final int PATH_TYPE_MASK = 0x3F;
    private static final int SIGNAL_PRESENT = 1 << 9;
    private static final int SIGNAL_VALUE = 1 << 10;
    private static final int ANALOG_PRESENT = 1 << 11;
    private static final int ANALOG_VALUE = 1 << 12;
    private static final int REDSTONE_PRESENT = 1 << 13;
    private static final int REDSTONE_VALUE = 1 << 14;
    private static final int REDSTONE_SKIP = 1 << 15;
    private static final int PATHFINDABLE_LAND_PRESENT = 1 << 16;
    private static final int PATHFINDABLE_LAND = 1 << 17;
    private static final int PATHFINDABLE_WATER_PRESENT = 1 << 18;
    private static final int PATHFINDABLE_WATER = 1 << 19;
    private static final int PATHFINDABLE_AIR_PRESENT = 1 << 20;
    private static final int PATHFINDABLE_AIR = 1 << 21;

    private BlockStatePropertyPack() {
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

    public static @Nullable PathType pathType(final int packed) {
        if ((packed & PATH_TYPE_PRESENT) == 0) {
            return null;
        }
        int ordinal = (packed >>> PATH_TYPE_SHIFT) & PATH_TYPE_MASK;
        PathType[] values = PathType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    public static int withPathType(final int packed, final PathType pathType) {
        int ordinal = pathType.ordinal();
        if (ordinal > PATH_TYPE_MASK) {
            return packed;
        }
        int cleared = packed & ~(PATH_TYPE_MASK << PATH_TYPE_SHIFT);
        return cleared | PATH_TYPE_PRESENT | (ordinal << PATH_TYPE_SHIFT);
    }

    public static @Nullable Boolean signalSource(final int packed) {
        return (packed & SIGNAL_PRESENT) == 0 ? null : (packed & SIGNAL_VALUE) != 0;
    }

    public static int withSignalSource(final int packed, final boolean value) {
        int next = packed | SIGNAL_PRESENT;
        return value ? next | SIGNAL_VALUE : next & ~SIGNAL_VALUE;
    }

    public static @Nullable Boolean analogOutput(final int packed) {
        return (packed & ANALOG_PRESENT) == 0 ? null : (packed & ANALOG_VALUE) != 0;
    }

    public static int withAnalogOutput(final int packed, final boolean value) {
        int next = packed | ANALOG_PRESENT;
        return value ? next | ANALOG_VALUE : next & ~ANALOG_VALUE;
    }

    public static boolean redstoneConductorSkipped(final int packed) {
        return (packed & REDSTONE_SKIP) != 0;
    }

    public static int skipRedstoneConductor(final int packed) {
        return packed | REDSTONE_SKIP;
    }

    public static @Nullable Boolean redstoneConductor(final int packed) {
        if ((packed & REDSTONE_SKIP) != 0 || (packed & REDSTONE_PRESENT) == 0) {
            return null;
        }
        return (packed & REDSTONE_VALUE) != 0;
    }

    public static int withRedstoneConductor(final int packed, final boolean value) {
        int next = (packed | REDSTONE_PRESENT) & ~REDSTONE_SKIP;
        return value ? next | REDSTONE_VALUE : next & ~REDSTONE_VALUE;
    }

    public static @Nullable Boolean pathfindable(final int packed, final PathComputationType type) {
        int present;
        int value;
        switch (type) {
            case LAND -> {
                present = PATHFINDABLE_LAND_PRESENT;
                value = PATHFINDABLE_LAND;
            }
            case WATER -> {
                present = PATHFINDABLE_WATER_PRESENT;
                value = PATHFINDABLE_WATER;
            }
            case AIR -> {
                present = PATHFINDABLE_AIR_PRESENT;
                value = PATHFINDABLE_AIR;
            }
            default -> {
                return null;
            }
        }
        if ((packed & present) == 0) {
            return null;
        }
        return (packed & value) != 0;
    }

    public static int withPathfindable(final int packed, final PathComputationType type, final boolean value) {
        int present;
        int bit;
        switch (type) {
            case LAND -> {
                present = PATHFINDABLE_LAND_PRESENT;
                bit = PATHFINDABLE_LAND;
            }
            case WATER -> {
                present = PATHFINDABLE_WATER_PRESENT;
                bit = PATHFINDABLE_WATER;
            }
            case AIR -> {
                present = PATHFINDABLE_AIR_PRESENT;
                bit = PATHFINDABLE_AIR;
            }
            default -> {
                return packed;
            }
        }
        int next = packed | present;
        return value ? next | bit : next & ~bit;
    }
}
