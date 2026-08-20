package dev.ultima.cache.state;

import dev.ultima.Ultima;
import dev.ultima.cache.CacheMetrics;
import dev.ultima.cache.VanillaClassGuard;
import dev.ultima.config.UltimaConfig;
import dev.ultima.failopen.FailOpenGuard;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.Nullable;

/**
 * Lazy identity-indexed memo of <em>proven-pure</em> BlockState/FluidState properties.
 *
 * <p>Vanilla already stores many properties as fields on {@code BlockStateBase} ({@code isAir},
 * {@code pushReaction}, {@code destroySpeed}, {@code mapColor}, {@code isRandomlyTicking}, ...).
 * This cache only covers methods that still compute on every call and that do not read world,
 * position (except as a formula input), block entities, or neighbors.
 *
 * <h2>Cached (vanilla-class states only)</h2>
 * <ul>
 *   <li>{@code WalkNodeEvaluator.getPathTypeFromState} result, unless Fabric registered a
 *       {@link LandPathTypeRegistry} provider for the block (static or dynamic — both skipped).</li>
 *   <li>{@code BlockState.isSignalSource()} — vanilla signature is {@code (BlockState)} only.</li>
 *   <li>{@code BlockState.hasAnalogOutputSignal()} — the boolean only; analog <em>value</em> is
 *       not cached (container/comparator module).</li>
 *   <li>{@code BlockState.isRedstoneConductor} only when {@code !block.hasDynamicShape()}.</li>
 *   <li>{@code BlockState.isPathfindable(PathComputationType)} — vanilla implementations take
 *       only the state (default even uses {@code EmptyBlockGetter}).</li>
 *   <li>FluidState {@code isSource}, {@code isEmpty}, {@code getAmount}, {@code getOwnHeight}.</li>
 * </ul>
 *
 * <h2>Explicitly not cached</h2>
 * {@code getSignal}/{@code getDirectSignal}/{@code getAnalogOutputSignal} (world + often BE),
 * explosion resistance (already a field), piston push reaction (already a field), map color
 * (already a field), anything that reads neighbors.
 *
 * <h2>Invalidation sources</h2>
 * <ol>
 *   <li>{@code MappedRegistry.refreshTagsInHolders} — PathType / WATER pathfindable / FIRE tags
 *       can change when tags rebind.</li>
 *   <li>{@code ServerLifecycleEvents.START_DATA_PACK_RELOAD}.</li>
 *   <li>{@code CommonLifecycleEvents.TAGS_LOADED}.</li>
 *   <li>{@code ServerLifecycleEvents.END_DATA_PACK_RELOAD} (success).</li>
 *   <li>Module disabled or table-size miss (id outside snapshot) — vanilla per probe.</li>
 * </ol>
 *
 * <p>Block/item/fluid/entity-type registries are frozen after bootstrap in 26.2, so state ids do
 * not grow. An out-of-range id still falls back instead of being stretched.
 */
public final class StatePropertyRuntime {
    public static final CacheMetrics METRICS = new CacheMetrics();

    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile int[] blockTable = new int[0];
    private static volatile int[] fluidTable = new int[0];
    private static volatile float[] fluidHeights = new float[0];

    private StatePropertyRuntime() {
    }

    public static boolean moduleEnabled() {
        try {
            return UltimaConfig.get().isEnabled("state_property_cache");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int generation() {
        return GENERATION.get();
    }

    public static int blockTableLengthForTest() {
        return blockTable.length;
    }

    public static void invalidateAll(final String reason) {
        FailOpenGuard.run(FailOpenGuard.Module.STATE_PROPERTY_CACHE, reason, () -> {
            GENERATION.incrementAndGet();
            int blockSize = 0;
            int fluidSize = 0;
            try {
                blockSize = Block.BLOCK_STATE_REGISTRY.size();
                fluidSize = Fluid.FLUID_STATE_REGISTRY.size();
            } catch (Throwable ignored) {
                blockSize = 0;
                fluidSize = 0;
            }
            blockTable = new int[Math.max(0, blockSize)];
            fluidTable = new int[Math.max(0, fluidSize)];
            fluidHeights = new float[Math.max(0, fluidSize)];
            METRICS.invalidations.incrementAndGet();
            Ultima.LOGGER.debug(
                    "Ultima state property cache invalidated ({}); blockStates={}, fluidStates={}",
                    reason,
                    blockSize,
                    fluidSize);
        });
    }

    public static @Nullable PathType pathTypeIfCached(final BlockState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> pathTypeIfCachedUnchecked(state));
    }

    public static void rememberPathType(final BlockState state, final @Nullable PathType pathType) {
        FailOpenGuard.run(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> {
            if (pathType == null || !mayCachePathType(state)) {
                return;
            }
            int id = blockId(state);
            int[] table = blockTable;
            if (id < 0 || id >= table.length) {
                return;
            }
            int packed = classifyBlock(table, id, state);
            if (BlockStatePropertyPack.isSkip(packed)) {
                return;
            }
            table[id] = BlockStatePropertyPack.withPathType(packed, pathType);
        });
    }

    public static boolean mayCachePathType(final BlockState state) {
        if (!moduleEnabled() || state == null || !VanillaClassGuard.isVanillaType(state.getBlock())) {
            return false;
        }
        try {
            if (LandPathTypeRegistry.getPathTypeProvider(state.getBlock()) != null) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    public static @Nullable Boolean signalSourceIfCached(final BlockState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> booleanProperty(state, BlockStatePropertyPack::signalSource));
    }

    public static void rememberSignalSource(final BlockState state, final boolean value) {
        FailOpenGuard.run(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> mutateBlock(state, packed -> BlockStatePropertyPack.withSignalSource(packed, value)));
    }

    public static @Nullable Boolean analogIfCached(final BlockState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> booleanProperty(state, BlockStatePropertyPack::analogOutput));
    }

    public static void rememberAnalog(final BlockState state, final boolean value) {
        FailOpenGuard.run(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> mutateBlock(state, packed -> BlockStatePropertyPack.withAnalogOutput(packed, value)));
    }

    public static @Nullable Boolean redstoneConductorIfCached(final BlockState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> redstoneConductorIfCachedUnchecked(state));
    }

    public static boolean mayCacheRedstoneConductor(final BlockState state) {
        return moduleEnabled()
                && state != null
                && VanillaClassGuard.isVanillaType(state.getBlock())
                && !state.getBlock().hasDynamicShape();
    }

    public static void noteUncacheableRedstoneConductor(final BlockState state) {
        if (mayCacheBlock(state) && !mayCacheRedstoneConductor(state)) {
            markRedstoneSkip(state);
        }
    }

    public static void rememberRedstoneConductor(final BlockState state, final boolean value) {
        FailOpenGuard.run(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> {
            if (!mayCacheRedstoneConductor(state)) {
                markRedstoneSkip(state);
                return;
            }
            mutateBlock(state, packed -> BlockStatePropertyPack.withRedstoneConductor(packed, value));
        });
    }

    public static @Nullable Boolean pathfindableIfCached(final BlockState state, final PathComputationType type) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> pathfindableIfCachedUnchecked(state, type));
    }

    public static void rememberPathfindable(
            final BlockState state, final PathComputationType type, final boolean value) {
        FailOpenGuard.run(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> {
            if (type == null) {
                return;
            }
            mutateBlock(state, packed -> BlockStatePropertyPack.withPathfindable(packed, type, value));
        });
    }

    public static @Nullable Boolean fluidSourceIfCached(final FluidState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> fluidBoolean(state, FluidStatePropertyPack::isSource));
    }

    public static void rememberFluidSource(final FluidState state, final boolean value) {
        FailOpenGuard.run(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> mutateFluid(state, packed -> FluidStatePropertyPack.withSource(packed, value)));
    }

    public static @Nullable Boolean fluidEmptyIfCached(final FluidState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> fluidBoolean(state, FluidStatePropertyPack::isEmpty));
    }

    public static void rememberFluidEmpty(final FluidState state, final boolean value) {
        FailOpenGuard.run(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> mutateFluid(state, packed -> FluidStatePropertyPack.withEmpty(packed, value)));
    }

    public static @Nullable Integer fluidAmountIfCached(final FluidState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> fluidAmountIfCachedUnchecked(state));
    }

    public static void rememberFluidAmount(final FluidState state, final int amount) {
        FailOpenGuard.run(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE,
                state,
                () -> mutateFluid(state, packed -> FluidStatePropertyPack.withAmount(packed, amount)));
    }

    public static @Nullable Float fluidHeightIfCached(final FluidState state) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> fluidHeightIfCachedUnchecked(state));
    }

    public static void rememberFluidHeight(final FluidState state, final float height) {
        FailOpenGuard.run(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> {
            int id = fluidId(state);
            int[] table = fluidTable;
            float[] heights = fluidHeights;
            if (id < 0 || id >= table.length || id >= heights.length) {
                return;
            }
            int packed = classifyFluid(table, id, state);
            if (FluidStatePropertyPack.isSkip(packed)) {
                return;
            }
            heights[id] = height;
            table[id] = FluidStatePropertyPack.withHeightPresent(packed);
        });
    }

    private static @Nullable PathType pathTypeIfCachedUnchecked(final BlockState state) {
        int packed = blockPacked(state);
        if (packed < 0) {
            return null;
        }
        if (BlockStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        PathType cached = BlockStatePropertyPack.pathType(packed);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static @Nullable Boolean redstoneConductorIfCachedUnchecked(final BlockState state) {
        int packed = blockPacked(state);
        if (packed < 0) {
            return null;
        }
        if (BlockStatePropertyPack.isSkip(packed) || BlockStatePropertyPack.redstoneConductorSkipped(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Boolean cached = BlockStatePropertyPack.redstoneConductor(packed);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static @Nullable Boolean pathfindableIfCachedUnchecked(
            final BlockState state, final PathComputationType type) {
        if (type == null) {
            return null;
        }
        int packed = blockPacked(state);
        if (packed < 0) {
            return null;
        }
        if (BlockStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Boolean cached = BlockStatePropertyPack.pathfindable(packed, type);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static @Nullable Integer fluidAmountIfCachedUnchecked(final FluidState state) {
        int packed = fluidPacked(state);
        if (packed < 0) {
            return null;
        }
        if (FluidStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Integer cached = FluidStatePropertyPack.amount(packed);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static @Nullable Float fluidHeightIfCachedUnchecked(final FluidState state) {
        int id = fluidId(state);
        int[] table = fluidTable;
        float[] heights = fluidHeights;
        if (id < 0 || id >= table.length || id >= heights.length) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        int packed = classifyFluid(table, id, state);
        if (FluidStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        if (!FluidStatePropertyPack.heightPresent(packed)) {
            METRICS.misses.incrementAndGet();
            return null;
        }
        METRICS.hits.incrementAndGet();
        return heights[id];
    }

    public static boolean mayCacheBlock(final BlockState state) {
        return moduleEnabled() && state != null && VanillaClassGuard.isVanillaType(state.getBlock());
    }

    public static boolean mayCacheFluid(final FluidState state) {
        return moduleEnabled() && state != null && VanillaClassGuard.isVanillaType(state.getType());
    }

    private static @Nullable Boolean booleanProperty(
            final BlockState state, final IntBooleanGetter getter) {
        int packed = blockPacked(state);
        if (packed < 0) {
            return null;
        }
        if (BlockStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Boolean cached = getter.get(packed);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static @Nullable Boolean fluidBoolean(
            final FluidState state, final IntBooleanGetter getter) {
        int packed = fluidPacked(state);
        if (packed < 0) {
            return null;
        }
        if (FluidStatePropertyPack.isSkip(packed)) {
            METRICS.fallbacks.incrementAndGet();
            return null;
        }
        Boolean cached = getter.get(packed);
        if (cached != null) {
            METRICS.hits.incrementAndGet();
        } else {
            METRICS.misses.incrementAndGet();
        }
        return cached;
    }

    private static int blockPacked(final BlockState state) {
        if (!mayCacheBlock(state)) {
            METRICS.fallbacks.incrementAndGet();
            return -1;
        }
        int id = blockId(state);
        int[] table = blockTable;
        if (id < 0 || id >= table.length) {
            METRICS.fallbacks.incrementAndGet();
            return -1;
        }
        return classifyBlock(table, id, state);
    }

    private static int fluidPacked(final FluidState state) {
        if (!mayCacheFluid(state)) {
            METRICS.fallbacks.incrementAndGet();
            return -1;
        }
        int id = fluidId(state);
        int[] table = fluidTable;
        if (id < 0 || id >= table.length) {
            METRICS.fallbacks.incrementAndGet();
            return -1;
        }
        return classifyFluid(table, id, state);
    }

    private static int classifyBlock(final int[] table, final int id, final BlockState state) {
        int packed = table[id];
        int kind = BlockStatePropertyPack.kind(packed);
        if (kind != BlockStatePropertyPack.KIND_UNSET) {
            return packed;
        }
        int classified = BlockStatePropertyPack.withKind(
                packed,
                VanillaClassGuard.isVanillaType(state.getBlock())
                        ? BlockStatePropertyPack.KIND_VANILLA
                        : BlockStatePropertyPack.KIND_SKIP);
        table[id] = classified;
        return classified;
    }

    private static int classifyFluid(final int[] table, final int id, final FluidState state) {
        int packed = table[id];
        int kind = FluidStatePropertyPack.kind(packed);
        if (kind != FluidStatePropertyPack.KIND_UNSET) {
            return packed;
        }
        int classified = FluidStatePropertyPack.withKind(
                packed,
                VanillaClassGuard.isVanillaType(state.getType())
                        ? FluidStatePropertyPack.KIND_VANILLA
                        : FluidStatePropertyPack.KIND_SKIP);
        table[id] = classified;
        return classified;
    }

    private static void mutateBlock(final BlockState state, final IntUnary packedOp) {
        if (!mayCacheBlock(state)) {
            return;
        }
        int id = blockId(state);
        int[] table = blockTable;
        if (id < 0 || id >= table.length) {
            return;
        }
        int packed = classifyBlock(table, id, state);
        if (BlockStatePropertyPack.isSkip(packed)) {
            return;
        }
        table[id] = packedOp.apply(packed);
    }

    private static void mutateFluid(final FluidState state, final IntUnary packedOp) {
        if (!mayCacheFluid(state)) {
            return;
        }
        int id = fluidId(state);
        int[] table = fluidTable;
        if (id < 0 || id >= table.length) {
            return;
        }
        int packed = classifyFluid(table, id, state);
        if (FluidStatePropertyPack.isSkip(packed)) {
            return;
        }
        table[id] = packedOp.apply(packed);
    }

    private static void markRedstoneSkip(final BlockState state) {
        int id = blockId(state);
        int[] table = blockTable;
        if (id < 0 || id >= table.length) {
            return;
        }
        int packed = classifyBlock(table, id, state);
        table[id] = BlockStatePropertyPack.skipRedstoneConductor(packed);
    }

    private static int blockId(final BlockState state) {
        try {
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int fluidId(final FluidState state) {
        try {
            return Fluid.FLUID_STATE_REGISTRY.getId(state);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @FunctionalInterface
    private interface IntBooleanGetter {
        @Nullable Boolean get(int packed);
    }

    @FunctionalInterface
    private interface IntUnary {
        int apply(int packed);
    }
}
