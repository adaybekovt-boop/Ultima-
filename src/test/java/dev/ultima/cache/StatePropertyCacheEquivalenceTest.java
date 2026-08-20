package dev.ultima.cache;

import dev.ultima.cache.state.BlockStatePropertyPack;
import dev.ultima.cache.state.FluidStatePropertyPack;
import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.config.UltimaModules;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Pack encoding, skip/fallback, simulated reload, 200-trial differential, optional live vanilla
 * BlockState comparison, and an isolated lookup microbenchmark. Not an FPS claim.
 */
public final class StatePropertyCacheEquivalenceTest {
    private static final int TRIALS = 200;

    private StatePropertyCacheEquivalenceTest() {
    }

    public static void run() {
        dev.ultima.failopen.Wave2FailOpenTest.run();
        testPackRoundTrip();
        testSkipKind();
        testPathfindableIndependentBits();
        testRedstoneSkipDoesNotReturnValue();
        testFluidPack();
        testVanillaClassGuard();
        testModuleDefaultOffAndLithium();
        testInvalidateBumpsGeneration();
        testDifferentialRandomPacks();
        testNegativeModdedSkip();
        testLiveVanillaStatesIfAvailable();
        runIsolatedLookupMicrobench();
        System.out.println("State property cache equivalence checks passed.");
    }

    private static void testPackRoundTrip() {
        int packed = BlockStatePropertyPack.withKind(0, BlockStatePropertyPack.KIND_VANILLA);
        packed = BlockStatePropertyPack.withSignalSource(packed, true);
        packed = BlockStatePropertyPack.withAnalogOutput(packed, false);
        packed = BlockStatePropertyPack.withRedstoneConductor(packed, true);
        packed = BlockStatePropertyPack.withPathType(packed, PathType.STICKY_HONEY);
        packed = BlockStatePropertyPack.withPathfindable(packed, PathComputationType.LAND, true);
        assertTrue(BlockStatePropertyPack.isVanilla(packed), "kind vanilla");
        assertEquals(Boolean.TRUE, BlockStatePropertyPack.signalSource(packed), "signal");
        assertEquals(Boolean.FALSE, BlockStatePropertyPack.analogOutput(packed), "analog");
        assertEquals(Boolean.TRUE, BlockStatePropertyPack.redstoneConductor(packed), "redstone");
        assertTrue(BlockStatePropertyPack.pathType(packed) == PathType.STICKY_HONEY, "path type");
        assertEquals(Boolean.TRUE, BlockStatePropertyPack.pathfindable(packed, PathComputationType.LAND), "land");
        assertTrue(BlockStatePropertyPack.pathfindable(packed, PathComputationType.WATER) == null, "water unset");
    }

    private static void testSkipKind() {
        int packed = BlockStatePropertyPack.withKind(0, BlockStatePropertyPack.KIND_SKIP);
        packed = BlockStatePropertyPack.withSignalSource(packed, true);
        assertTrue(BlockStatePropertyPack.isSkip(packed), "modded skip");
        assertEquals(Boolean.TRUE, BlockStatePropertyPack.signalSource(packed), "pack still stores bits");
    }

    private static void testPathfindableIndependentBits() {
        int packed = BlockStatePropertyPack.withPathfindable(0, PathComputationType.AIR, false);
        assertEquals(Boolean.FALSE, BlockStatePropertyPack.pathfindable(packed, PathComputationType.AIR), "air false");
        assertTrue(BlockStatePropertyPack.pathfindable(packed, PathComputationType.LAND) == null, "land still unset");
    }

    private static void testRedstoneSkipDoesNotReturnValue() {
        int packed = BlockStatePropertyPack.withRedstoneConductor(0, true);
        packed = BlockStatePropertyPack.skipRedstoneConductor(packed);
        assertTrue(BlockStatePropertyPack.redstoneConductorSkipped(packed), "skip flag");
        assertTrue(BlockStatePropertyPack.redstoneConductor(packed) == null, "skip hides the value");
    }

    private static void testFluidPack() {
        int packed = FluidStatePropertyPack.withKind(0, FluidStatePropertyPack.KIND_VANILLA);
        packed = FluidStatePropertyPack.withSource(packed, true);
        packed = FluidStatePropertyPack.withEmpty(packed, false);
        packed = FluidStatePropertyPack.withAmount(packed, 8);
        packed = FluidStatePropertyPack.withHeightPresent(packed);
        assertEquals(Boolean.TRUE, FluidStatePropertyPack.isSource(packed), "source");
        assertEquals(Boolean.FALSE, FluidStatePropertyPack.isEmpty(packed), "empty");
        assertEquals(8, FluidStatePropertyPack.amount(packed), "amount");
        assertTrue(FluidStatePropertyPack.heightPresent(packed), "height bit");
    }

    private static void testVanillaClassGuard() {
        assertTrue(VanillaClassGuard.isVanillaClass(PathType.class), "PathType is vanilla");
        assertTrue(VanillaClassGuard.isVanillaClass(BlockStatePropertyPack.class) == false, "Ultima pack is not vanilla");
        assertTrue(VanillaClassGuard.isVanillaType("not a block") == false, "String is not vanilla gameplay state");
        assertTrue(VanillaClassGuard.isVanillaType(null) == false, "null is unproven");
    }

    private static void testModuleDefaultOffAndLithium() {
        UltimaModules.Module module = UltimaModules.byKey("state_property_cache");
        assertTrue(module != null, "state_property_cache is registered");
        assertTrue(!module.enabledByDefault(), "state_property_cache default off");
        assertTrue(module.incompatibleMods().contains("lithium"), "Lithium overlap");
        assertTrue(module.incompatibleMods().contains("canary"), "Canary is a Lithium fork");
        assertTrue(!module.clientOnly(), "property cache is common/server-safe");
        assertTrue(UltimaModules.isOptInExperiment("state_property_cache"), "opt-in experiment");
        assertTrue(UltimaModules.isOptInExperiment("tag_bitsets"), "tag_bitsets is opt-in");
    }

    private static void testInvalidateBumpsGeneration() {
        int before = StatePropertyRuntime.generation();
        StatePropertyRuntime.invalidateAll("test");
        assertTrue(StatePropertyRuntime.generation() > before, "invalidation bumps generation");
    }

    private static void testDifferentialRandomPacks() {
        Random random = new Random(0x50524F5053L);
        PathType[] types = PathType.values();
        PathComputationType[] pathTypes = PathComputationType.values();
        int probes = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            boolean skip = random.nextBoolean() && random.nextBoolean();
            int packed = BlockStatePropertyPack.withKind(
                    0, skip ? BlockStatePropertyPack.KIND_SKIP : BlockStatePropertyPack.KIND_VANILLA);
            PathType pathType = types[random.nextInt(types.length)];
            boolean signal = random.nextBoolean();
            boolean analog = random.nextBoolean();
            boolean redstone = random.nextBoolean();
            boolean dynamic = random.nextBoolean();
            boolean land = random.nextBoolean();
            packed = BlockStatePropertyPack.withPathType(packed, pathType);
            packed = BlockStatePropertyPack.withSignalSource(packed, signal);
            packed = BlockStatePropertyPack.withAnalogOutput(packed, analog);
            if (dynamic) {
                packed = BlockStatePropertyPack.skipRedstoneConductor(packed);
            } else {
                packed = BlockStatePropertyPack.withRedstoneConductor(packed, redstone);
            }
            packed = BlockStatePropertyPack.withPathfindable(packed, PathComputationType.LAND, land);

            if (BlockStatePropertyPack.pathType(packed) != pathType) {
                throw new AssertionError("pathType trial " + trial);
            }
            if (Boolean.TRUE.equals(BlockStatePropertyPack.signalSource(packed)) != signal) {
                throw new AssertionError("signal trial " + trial);
            }
            if (Boolean.TRUE.equals(BlockStatePropertyPack.analogOutput(packed)) != analog) {
                throw new AssertionError("analog trial " + trial);
            }
            if (dynamic) {
                if (BlockStatePropertyPack.redstoneConductor(packed) != null) {
                    throw new AssertionError("dynamic redstone must miss trial " + trial);
                }
            } else if (Boolean.TRUE.equals(BlockStatePropertyPack.redstoneConductor(packed)) != redstone) {
                throw new AssertionError("redstone trial " + trial);
            }
            if (Boolean.TRUE.equals(BlockStatePropertyPack.pathfindable(packed, PathComputationType.LAND)) != land) {
                throw new AssertionError("land trial " + trial);
            }
            for (PathComputationType type : pathTypes) {
                if (type != PathComputationType.LAND && BlockStatePropertyPack.pathfindable(packed, type) != null) {
                    throw new AssertionError("unset pathfindable " + type + " trial " + trial);
                }
            }
            probes++;
        }
        System.out.println("State property pack differential: " + probes + " trials, 0 mismatches.");
    }

    private static void testNegativeModdedSkip() {
        int packed = BlockStatePropertyPack.withKind(0, BlockStatePropertyPack.KIND_SKIP);
        packed = BlockStatePropertyPack.withPathType(packed, PathType.OPEN);
        assertTrue(BlockStatePropertyPack.isSkip(packed), "modded state is skip");
        assertTrue(
                VanillaClassGuard.isVanillaClass(StatePropertyCacheEquivalenceTest.class) == false,
                "this test class is the documented modded/unproven edge case");
    }

    private static void testLiveVanillaStatesIfAvailable() {
        if (!tryBootstrap()) {
            System.out.println("Skipping live BlockState/FluidState differential: bootstrap unavailable.");
            return;
        }
        int compared = 0;
        int redstoneSkipped = 0;
        BlockPos origin = BlockPos.ZERO;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (state == null || !VanillaClassGuard.isVanillaType(state.getBlock())) {
                continue;
            }
            compared++;
            boolean signal = state.isSignalSource();
            boolean analog = state.hasAnalogOutputSignal();
            boolean land = state.isPathfindable(PathComputationType.LAND);
            boolean water = state.isPathfindable(PathComputationType.WATER);
            boolean air = state.isPathfindable(PathComputationType.AIR);
            int packed = BlockStatePropertyPack.withKind(0, BlockStatePropertyPack.KIND_VANILLA);
            packed = BlockStatePropertyPack.withSignalSource(packed, signal);
            packed = BlockStatePropertyPack.withAnalogOutput(packed, analog);
            packed = BlockStatePropertyPack.withPathfindable(packed, PathComputationType.LAND, land);
            packed = BlockStatePropertyPack.withPathfindable(packed, PathComputationType.WATER, water);
            packed = BlockStatePropertyPack.withPathfindable(packed, PathComputationType.AIR, air);
            if (Boolean.TRUE.equals(BlockStatePropertyPack.signalSource(packed)) != signal
                    || Boolean.TRUE.equals(BlockStatePropertyPack.analogOutput(packed)) != analog
                    || Boolean.TRUE.equals(BlockStatePropertyPack.pathfindable(packed, PathComputationType.LAND)) != land
                    || Boolean.TRUE.equals(BlockStatePropertyPack.pathfindable(packed, PathComputationType.WATER)) != water
                    || Boolean.TRUE.equals(BlockStatePropertyPack.pathfindable(packed, PathComputationType.AIR)) != air) {
                throw new AssertionError("vanilla BlockState pack mismatch " + state);
            }
            if (state.getBlock().hasDynamicShape()) {
                packed = BlockStatePropertyPack.skipRedstoneConductor(packed);
                if (BlockStatePropertyPack.redstoneConductor(packed) != null) {
                    throw new AssertionError("dynamic-shape redstone must not be cached " + state);
                }
                redstoneSkipped++;
            } else {
                boolean conductor = state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, origin);
                packed = BlockStatePropertyPack.withRedstoneConductor(packed, conductor);
                if (Boolean.TRUE.equals(BlockStatePropertyPack.redstoneConductor(packed)) != conductor) {
                    throw new AssertionError("redstone conductor mismatch " + state);
                }
            }
            packed = BlockStatePropertyPack.withPathType(packed, PathType.WALKABLE);
            if (BlockStatePropertyPack.pathType(packed) != PathType.WALKABLE) {
                throw new AssertionError("PathType pack roundtrip " + state);
            }
            if (compared >= 4096) {
                break;
            }
        }
        int fluids = 0;
        for (FluidState fluid : Fluid.FLUID_STATE_REGISTRY) {
            if (fluid == null || !VanillaClassGuard.isVanillaType(fluid.getType())) {
                continue;
            }
            int packed = FluidStatePropertyPack.withKind(0, FluidStatePropertyPack.KIND_VANILLA);
            packed = FluidStatePropertyPack.withSource(packed, fluid.isSource());
            packed = FluidStatePropertyPack.withEmpty(packed, fluid.isEmpty());
            packed = FluidStatePropertyPack.withAmount(packed, fluid.getAmount());
            if (Boolean.TRUE.equals(FluidStatePropertyPack.isSource(packed)) != fluid.isSource()
                    || Boolean.TRUE.equals(FluidStatePropertyPack.isEmpty(packed)) != fluid.isEmpty()
                    || FluidStatePropertyPack.amount(packed) == null
                    || FluidStatePropertyPack.amount(packed) != fluid.getAmount()) {
                throw new AssertionError("vanilla FluidState pack mismatch " + fluid);
            }
            fluids++;
        }
        System.out.println(
                "Live vanilla differential: blockStates="
                        + compared
                        + " redstoneSkippedDynamic="
                        + redstoneSkipped
                        + " fluidStates="
                        + fluids);
    }

    private static void runIsolatedLookupMicrobench() {
        int packed = BlockStatePropertyPack.withKind(0, BlockStatePropertyPack.KIND_VANILLA);
        packed = BlockStatePropertyPack.withSignalSource(packed, true);
        packed = BlockStatePropertyPack.withPathType(packed, PathType.WALKABLE);
        Boolean sink = Boolean.FALSE;
        PathType typeSink = PathType.OPEN;
        for (int i = 0; i < 10_000; i++) {
            sink = BlockStatePropertyPack.signalSource(packed);
            typeSink = BlockStatePropertyPack.pathType(packed);
        }
        final int iterations = 1_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink = BlockStatePropertyPack.signalSource(packed);
            typeSink = BlockStatePropertyPack.pathType(packed);
        }
        long packedNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink = Boolean.TRUE;
            typeSink = PathType.WALKABLE;
        }
        long baselineNs = System.nanoTime() - t0;
        if (sink == null || typeSink == null) {
            throw new AssertionError("bench sink");
        }
        System.out.printf(
                "Isolated property-lookup microbench (NOT an FPS claim): packed=%.2f ns/op baseline-assign=%.2f ns/op%n",
                packedNs / (double)iterations,
                baselineNs / (double)iterations);
    }

    private static boolean tryBootstrap() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            return Block.BLOCK_STATE_REGISTRY.size() > 0;
        } catch (Throwable t) {
            System.out.println("Bootstrap failed: " + t);
            return false;
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final int expected, final Integer actual, final String message) {
        if (actual == null || actual != expected) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
