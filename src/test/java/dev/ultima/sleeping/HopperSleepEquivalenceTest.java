package dev.ultima.sleeping;

import dev.ultima.config.UltimaModules;
import dev.ultima.sleeping.hopper.HopperSleepPolicy;
import dev.ultima.sleeping.hopper.HopperSleepSnapshot;
import dev.ultima.sleeping.hopper.HopperWakeCondition;
import dev.ultima.sleeping.hopper.HopperWakeTable;
import dev.ultima.sleeping.hopper.NeighborKind;
import dev.ultima.sleeping.hopper.NeighborState;
import dev.ultima.sleeping.hopper.Occupancy;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Equivalence, safety, differential, and circuit-breaker checks for hopper sleeping.
 */
public final class HopperSleepEquivalenceTest {
    private HopperSleepEquivalenceTest() {
    }

    public static void run() {
        testWakeTableCoversEveryCondition();
        testLockedHopperSleeps();
        testIdleEmptyHopperSleepsWithAirNeighbors();
        testFullHopperSleepsWhenDestFull();
        testUnknownNeighborForbidsSleep();
        testFurnaceClassIsNotSleepSafe();
        testLootNeighborForbidsSleep();
        testSubclassForbidsSleep();
        testOnCooldownDoesNotSleep();
        testCanPushDoesNotSleep();
        testItemEntitiesWaitingForbidSleep();
        testSilentSkipItemEntityAboveRequiresChannel();
        testUnlockWakeThenIdleResleep();
        testInventoryWake();
        testNeighborInventoryWake();
        testBlockUpdateWake();
        testItemEntityWake();
        testCircuitBreakerPinsThrash();
        testFailOpenPinsOnlyFaultingHopper();
        testHopperChainCooldownMatchesVanilla();
        testDifferentialRandomTrace();
        testMetricsKeys();
        testModuleDefaultOffAndLithium();
        testWorldUnloadClearsWakeRegistry();
        System.out.println("Hopper sleep equivalence checks passed.");
    }

    private static void testWakeTableCoversEveryCondition() {
        List<HopperWakeTable.Row> rows = HopperWakeTable.rows();
        assertEquals(HopperWakeCondition.values().length, rows.size(), "table rows match enum");
        EnumSet<HopperWakeCondition> seen = EnumSet.noneOf(HopperWakeCondition.class);
        for (HopperWakeTable.Row row : rows) {
            seen.add(row.condition());
            if (row.condition().sleepForbiddenWhenRelevant()) {
                assertTrue(row.condition().channel() == null, row.condition() + " must have no channel");
            } else {
                assertTrue(row.condition().channel() != null, row.condition() + " must have a channel");
            }
        }
        assertEquals(HopperWakeCondition.values().length, seen.size(), "every condition appears once");
    }

    private static void testLockedHopperSleeps() {
        SleepDecision decision = HopperSleepPolicy.evaluate(HopperSleepSnapshot.lockedVanillaHopper());
        assertTrue(decision.allowed(), "locked hopper sleeps: " + decision.reason());
        assertTrue(decision.requiredChannels().contains(WakeChannel.BLOCK_UPDATE), "lock needs block update");
    }

    private static void testIdleEmptyHopperSleepsWithAirNeighbors() {
        HopperSleepSnapshot snapshot = idleEmptyAir();
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        assertTrue(decision.allowed(), "idle empty hopper sleeps: " + decision.reason());
        assertTrue(decision.requiredChannels().contains(WakeChannel.ITEM_ENTITY), "air above needs item-entity wake");
        assertTrue(decision.requiredChannels().contains(WakeChannel.CONTAINER_ENTITY), "air dest needs container-entity wake");
    }

    private static void testFullHopperSleepsWhenDestFull() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                true,
                0,
                false,
                true,
                false,
                NeighborState.sleepSafe(Occupancy.EMPTY),
                NeighborState.sleepSafe(Occupancy.FULL),
                true,
                false);
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        assertTrue(decision.allowed(), "full hopper into full dest sleeps: " + decision.reason());
        assertTrue(decision.requiredChannels().contains(WakeChannel.NEIGHBOR_INVENTORY), "full dest needs inventory wake");
    }

    private static void testUnknownNeighborForbidsSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                true,
                0,
                true,
                false,
                false,
                NeighborState.unknown(),
                NeighborState.absent(),
                false,
                false);
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        assertFalse(decision.allowed(), "unknown source must not sleep");
        assertTrue(decision.reason().contains("NON_ALLOWLISTED"), "reason=" + decision.reason());
    }

    private static void testFurnaceClassIsNotSleepSafe() {
        assertFalse(
                VanillaContainerClassifier.isSleepSafeClass(AbstractFurnaceBlockEntity.class),
                "furnace is an internal-tick mutator");
        assertTrue(
                VanillaContainerClassifier.isSleepSafeClass(ChestBlockEntity.class),
                "chest is allowlisted");
        assertTrue(
                VanillaContainerClassifier.isSleepSafeClass(HopperBlockEntity.class),
                "hopper is allowlisted");
    }

    private static void testLootNeighborForbidsSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                true,
                0,
                true,
                false,
                false,
                new NeighborState(NeighborKind.UNKNOWN, Occupancy.NA, true),
                NeighborState.absent(),
                false,
                false);
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        assertFalse(decision.allowed(), "loot source must not sleep");
        assertTrue(decision.reason().contains("LOOT"), "reason=" + decision.reason());
    }

    private static void testSubclassForbidsSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                false,
                false,
                0,
                true,
                false,
                false,
                NeighborState.absent(),
                NeighborState.absent(),
                false,
                false);
        assertFalse(HopperSleepPolicy.evaluate(snapshot).allowed(), "modded hopper subclass must not sleep");
    }

    private static void testOnCooldownDoesNotSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                false,
                8,
                true,
                false,
                false,
                NeighborState.absent(),
                NeighborState.absent(),
                false,
                false);
        assertFalse(HopperSleepPolicy.evaluate(snapshot).allowed(), "cooldown ticks stay on vanilla bookkeeping");
    }

    private static void testCanPushDoesNotSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                true,
                0,
                false,
                false,
                false,
                NeighborState.absent(),
                NeighborState.sleepSafe(Occupancy.EMPTY),
                true,
                false);
        assertFalse(HopperSleepPolicy.evaluate(snapshot).allowed(), "hopper with items and empty dest must tick");
    }

    private static void testItemEntitiesWaitingForbidSleep() {
        HopperSleepSnapshot snapshot = new HopperSleepSnapshot(
                true,
                true,
                0,
                true,
                false,
                false,
                NeighborState.absent(),
                NeighborState.absent(),
                false,
                true);
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        assertFalse(decision.allowed(), "items already in suck AABB must be collected");
        assertEquals("ITEMS_WAITING_IN_SUCK_AABB", decision.reason(), "waiting items reason");
    }

    private static void testSilentSkipItemEntityAboveRequiresChannel() {
        HopperSleepSnapshot snapshot = idleEmptyAir();
        SleepDecision naive = HopperSleepPolicy.evaluateWithoutItemEntityWake(snapshot);
        assertFalse(naive.allowed(), "entityInside-only wake would miss items in the block above");
        assertTrue(
                naive.reason().contains("ITEM_ENTITY") || naive.reason().contains("missing_wake"),
                "naive miss is refused, reason=" + naive.reason());
        SleepDecision covered = HopperSleepPolicy.evaluate(snapshot);
        assertTrue(covered.allowed(), "full channel set allows sleep until an item actually appears");
    }

    private static void testUnlockWakeThenIdleResleep() {
        SleepController controller = new SleepController();
        BlockEntitySleepMetrics.resetForTests();
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(HopperSleepSnapshot.lockedVanillaHopper()), 10L);
        assertTrue(controller.isSleeping(), "starts sleeping while locked");
        controller.wake(WakeChannel.BLOCK_UPDATE, 11L);
        assertTrue(controller.state() == SleepState.ACTIVE, "unlock wakes");
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(HopperSleepSnapshot.lockedVanillaHopper()), 12L);
        assertTrue(controller.isSleeping(), "can sleep again after a quiet unlock/relock");
    }

    private static void testInventoryWake() {
        SleepController controller = new SleepController();
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(idleEmptyAir()), 0L);
        controller.wake(WakeChannel.SELF_INVENTORY, 1L);
        assertTrue(controller.state() == SleepState.ACTIVE, "own inventory mutation wakes");
    }

    private static void testNeighborInventoryWake() {
        SleepController controller = new SleepController();
        HopperSleepSnapshot full = new HopperSleepSnapshot(
                true,
                true,
                0,
                false,
                true,
                false,
                NeighborState.sleepSafe(Occupancy.EMPTY),
                NeighborState.sleepSafe(Occupancy.FULL),
                true,
                false);
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(full), 0L);
        controller.wake(WakeChannel.NEIGHBOR_INVENTORY, 1L);
        assertTrue(controller.state() == SleepState.ACTIVE, "dest inventory mutation wakes");
    }

    private static void testBlockUpdateWake() {
        SleepController controller = new SleepController();
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(idleEmptyAir()), 0L);
        controller.wake(WakeChannel.BLOCK_UPDATE, 3L);
        assertTrue(controller.state() == SleepState.ACTIVE, "neighbour block update wakes");
    }

    private static void testItemEntityWake() {
        SleepController controller = new SleepController();
        controller.enterSleepForTest(HopperSleepPolicy.evaluate(idleEmptyAir()), 0L);
        controller.wake(WakeChannel.ITEM_ENTITY, 4L);
        assertTrue(controller.state() == SleepState.ACTIVE, "item entity in suck AABB wakes");
    }

    private static void testCircuitBreakerPinsThrash() {
        BlockEntitySleepMetrics.resetForTests();
        SleepController controller = new SleepController();
        SleepDecision allow = HopperSleepPolicy.evaluate(HopperSleepSnapshot.lockedVanillaHopper());
        for (int cycle = 0; cycle < SleepCircuitBreaker.MAX_EMPTY_CYCLES; cycle++) {
            long t = cycle * 2L;
            controller.enterSleepForTest(allow, t);
            assertTrue(controller.isSleeping(), "cycle " + cycle + " sleeps");
            controller.wake(WakeChannel.BLOCK_UPDATE, t + 1L);
        }
        assertTrue(controller.isPinned(), "thrash without work must pin to polling");
        assertTrue(BlockEntitySleepMetrics.thrashTrips() >= 1L, "thrash is counted");
        controller.enterSleepForTest(allow, 100L);
        assertTrue(controller.isPinned(), "pinned hopper cannot sleep again");
    }

    private static void testFailOpenPinsOnlyFaultingHopper() {
        BlockEntitySleepMetrics.resetForTests();
        HopperSleepFailOpen.clearTestFault();
        HopperWorld faulty = HopperWorld.idleSetup();
        HopperWorld other = HopperWorld.idleSetup();
        faulty.useSleep = true;
        other.useSleep = true;
        faulty.id = "hopper-fault";
        other.id = "hopper-ok";
        faulty.tick();
        other.tick();
        assertTrue(faulty.controller.isSleeping(), "faulting hopper entered sleep");
        assertTrue(other.controller.isSleeping(), "other hopper entered sleep");

        HopperSleepFailOpen.armTestFault("hopper-fault");
        try {
            faulty.tick();
        } catch (Throwable error) {
            throw new AssertionError("sleep-check fault must not escape the server tick", error);
        }
        assertTrue(faulty.controller.isPinned(), "faulting hopper fail-opens to vanilla polling");
        assertTrue(
                faulty.controller.lastRefuseReason().startsWith("fail_open:"),
                "pin reason is fail-open, not a silent skip: " + faulty.controller.lastRefuseReason());
        assertFalse(HopperSleepFailOpen.testFaultArmed(), "test fault is consumed once");

        try {
            other.tick();
        } catch (Throwable error) {
            throw new AssertionError("other hopper tick must not see the faulting hopper's exception", error);
        }
        assertTrue(other.controller.isSleeping(), "other hopper is still sleeping");
        assertFalse(other.controller.isPinned(), "other hopper was not pinned");
        assertEquals(faulty.hopper.tickedGameTime, other.hopper.tickedGameTime, "both hoppers still ticked");
        HopperSleepFailOpen.clearTestFault();
    }

    private static void testWorldUnloadClearsWakeRegistry() {
        BlockEntitySleepRuntime.clearAll();
        assertFalse(BlockEntitySleepRuntime.registry().hasWatchers(), "clearAll drops wake subscriptions");
        String init;
        try {
            init = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/ultima/Ultima.java"));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read Ultima initializer", e);
        }
        assertTrue(init.contains("ServerLifecycleEvents.SERVER_STOPPED"), "clears on dedicated/integrated stop");
        assertTrue(init.contains("ServerWorldEvents.UNLOAD"), "clears on world unload including singleplayer leave");
        assertTrue(init.contains("BlockEntitySleepRuntime.clearAll()"), "SERVER_STOPPED calls clearAll");
        assertTrue(init.contains("BlockEntitySleepRuntime.clearLevel(world)"), "UNLOAD calls clearLevel");
    }

    private static void testHopperChainCooldownMatchesVanilla() {
        HopperWorld vanilla = HopperWorld.chain();
        HopperWorld sleeping = HopperWorld.chain();
        sleeping.useSleep = true;
        for (int tick = 0; tick < 5; tick++) {
            vanilla.tick();
            sleeping.tick();
        }
        vanilla.hopper.items[0] = 1;
        sleeping.hopper.items[0] = 1;
        sleeping.wakeSelf();
        vanilla.tick();
        sleeping.tick();
        assertEquals(vanilla.dest.cooldown, sleeping.dest.cooldown, "hopper-chain cooldown must match");
        assertEquals(vanilla.dest.tickedGameTime, sleeping.dest.tickedGameTime, "tickedGameTime must match");
        assertTrue(Arrays.equals(vanilla.dest.items, sleeping.dest.items), "dest items must match");
        assertTrue(Arrays.equals(vanilla.hopper.items, sleeping.hopper.items), "source hopper items must match");
    }

    private static void testDifferentialRandomTrace() {
        Random random = new Random(0x42455F534C45L);
        for (int trial = 0; trial < 200; trial++) {
            HopperWorld vanilla = HopperWorld.idleSetup();
            HopperWorld sleeping = HopperWorld.idleSetup();
            sleeping.useSleep = true;
            int steps = 80 + random.nextInt(40);
            for (int step = 0; step < steps; step++) {
                int op = random.nextInt(10);
                applyRandomOp(vanilla, sleeping, random, op);
                vanilla.tick();
                sleeping.tick();
                if (!vanilla.sameObservable(sleeping)) {
                    throw new AssertionError("differential mismatch trial=" + trial + " step=" + step
                            + " vanilla=" + vanilla.observe() + " sleeping=" + sleeping.observe());
                }
            }
        }
    }

    private static void testMetricsKeys() {
        BlockEntitySleepMetrics.resetForTests();
        assertTrue(BlockEntitySleepMetrics.snapshot().containsKey("be.sleeping"), "be.sleeping");
        assertTrue(BlockEntitySleepMetrics.snapshot().containsKey("be.wakeups"), "be.wakeups");
        assertTrue(BlockEntitySleepMetrics.snapshot().containsKey("be.ticks_skipped"), "be.ticks_skipped");
        assertTrue(BlockEntitySleepMetrics.snapshot().containsKey("be.thrashes"), "be.thrashes");
    }

    private static void testModuleDefaultOffAndLithium() {
        UltimaModules.Module module = UltimaModules.byKey("blockentity_sleeping");
        assertTrue(module != null, "module is registered");
        assertFalse(module.enabledByDefault(), "prototype stays default off");
        assertTrue(module.incompatibleMods().contains("lithium"), "Lithium overlap");
        assertTrue(module.incompatibleMods().contains("canary"), "Canary overlap");
        assertTrue(module.incompatibleMods().contains("radium"), "Radium overlap");
        assertTrue(UltimaModules.isOptInExperiment("blockentity_sleeping"), "opt-in experiment");
        assertFalse(module.clientOnly(), "server simulation module");
    }

    private static HopperSleepSnapshot idleEmptyAir() {
        return new HopperSleepSnapshot(
                true,
                true,
                0,
                true,
                false,
                false,
                NeighborState.absent(),
                NeighborState.absent(),
                false,
                false);
    }

    private static void applyRandomOp(
            final HopperWorld vanilla, final HopperWorld sleeping, final Random random, final int op) {
        switch (op) {
            case 0 -> {
                boolean enabled = random.nextBoolean();
                vanilla.setEnabled(enabled);
                sleeping.setEnabled(enabled);
            }
            case 1 -> {
                int slot = random.nextInt(5);
                int count = random.nextInt(5);
                vanilla.hopper.items[slot] = count;
                sleeping.hopper.items[slot] = count;
                vanilla.wakeSelf();
                sleeping.wakeSelf();
            }
            case 2 -> {
                int count = random.nextInt(65);
                vanilla.dest.items[0] = count;
                sleeping.dest.items[0] = count;
                vanilla.wakeDest();
                sleeping.wakeDest();
            }
            case 3 -> {
                int count = random.nextInt(5);
                vanilla.source.items[0] = count;
                sleeping.source.items[0] = count;
                vanilla.wakeSource();
                sleeping.wakeSource();
            }
            case 4 -> {
                boolean present = random.nextBoolean();
                vanilla.destPresent = present;
                sleeping.destPresent = present;
                vanilla.wakeBlock();
                sleeping.wakeBlock();
            }
            case 5 -> {
                boolean present = random.nextBoolean();
                vanilla.sourcePresent = present;
                sleeping.sourcePresent = present;
                vanilla.wakeBlock();
                sleeping.wakeBlock();
            }
            case 6 -> {
                int n = random.nextInt(3);
                vanilla.itemEntities = n;
                sleeping.itemEntities = n;
                vanilla.wakeItems();
                sleeping.wakeItems();
            }
            case 7 -> {
                vanilla.unknownDest = true;
                sleeping.unknownDest = true;
                vanilla.wakeBlock();
                sleeping.wakeBlock();
            }
            case 8 -> {
                vanilla.unknownDest = false;
                sleeping.unknownDest = false;
                vanilla.wakeBlock();
                sleeping.wakeBlock();
            }
            default -> {
            }
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final String expected, final String actual, final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    /**
     * Minimal hopper world matching 26.2 {@code pushItemsTick}/{@code tryMoveItems} for one item type.
     */
    private static final class HopperWorld {
        private final Hopper hopper = new Hopper();
        private final Hopper dest = new Hopper();
        private final Hopper source = new Hopper();
        private boolean destPresent = true;
        private boolean sourcePresent;
        private boolean unknownDest;
        private int itemEntities;
        private boolean enabled = true;
        private boolean useSleep;
        private final SleepController controller = new SleepController();
        private long time;
        private String id = "hopper";

        static HopperWorld idleSetup() {
            HopperWorld world = new HopperWorld();
            world.destPresent = false;
            world.sourcePresent = false;
            world.enabled = true;
            return world;
        }

        static HopperWorld chain() {
            HopperWorld world = new HopperWorld();
            world.destPresent = true;
            world.sourcePresent = false;
            world.enabled = true;
            world.hopper.items[0] = 0;
            world.dest.items[0] = 0;
            return world;
        }

        void setEnabled(final boolean enabled) {
            this.enabled = enabled;
            this.controller.wake(WakeChannel.BLOCK_UPDATE, this.time);
        }

        void wakeSelf() {
            this.controller.wake(WakeChannel.SELF_INVENTORY, this.time);
        }

        void wakeDest() {
            this.controller.wake(WakeChannel.NEIGHBOR_INVENTORY, this.time);
        }

        void wakeSource() {
            this.controller.wake(WakeChannel.NEIGHBOR_INVENTORY, this.time);
        }

        void wakeBlock() {
            this.controller.wake(WakeChannel.BLOCK_UPDATE, this.time);
        }

        void wakeItems() {
            this.controller.wake(WakeChannel.ITEM_ENTITY, this.time);
        }

        void tick() {
            this.time++;
            this.hopper.cooldown--;
            this.hopper.tickedGameTime = this.time;
            if (this.hopper.cooldown < 0) {
                this.hopper.cooldown = 0;
            }
            this.dest.tickedGameTime = this.time;
            if (this.hopper.cooldown == 0) {
                boolean skip = false;
                if (this.useSleep) {
                    skip = HopperSleepFailOpen.guardBoolean(this.id, this.controller, () -> {
                        if (!this.controller.isSleeping()) {
                            return false;
                        }
                        BlockEntitySleepMetrics.onTickSkipped();
                        return true;
                    });
                }
                if (!skip) {
                    boolean changed = this.tryMoveItems();
                    if (changed) {
                        this.hopper.cooldown = 8;
                        this.controller.recordWork();
                    } else if (this.useSleep) {
                        HopperSleepFailOpen.runVoid(this.id, this.controller, () -> this.controller.enterSleepForTest(
                                HopperSleepPolicy.evaluate(this.snapshot()), this.time));
                    }
                }
            }
        }

        private boolean tryMoveItems() {
            if (this.hopper.cooldown != 0 || !this.enabled) {
                return false;
            }
            boolean changed = false;
            if (!this.hopper.isEmpty()) {
                changed = this.eject();
            }
            if (!this.hopper.isFull()) {
                changed |= this.suck();
            }
            return changed;
        }

        private boolean eject() {
            if (!this.destPresent || this.unknownDest || this.dest.isFull()) {
                return false;
            }
            for (int slot = 0; slot < this.hopper.items.length; slot++) {
                if (this.hopper.items[slot] > 0) {
                    boolean destWasEmpty = this.dest.isEmpty();
                    this.hopper.items[slot]--;
                    this.dest.items[0]++;
                    if (destWasEmpty && this.dest.cooldown <= 8) {
                        int skip = this.dest.tickedGameTime >= this.hopper.tickedGameTime ? 1 : 0;
                        this.dest.cooldown = 8 - skip;
                    }
                    return true;
                }
            }
            return false;
        }

        private boolean suck() {
            if (this.sourcePresent) {
                if (this.source.isEmpty()) {
                    return false;
                }
                this.source.items[0]--;
                this.hopper.items[0]++;
                return true;
            }
            if (this.itemEntities > 0) {
                this.itemEntities--;
                this.hopper.items[0]++;
                return true;
            }
            return false;
        }

        private HopperSleepSnapshot snapshot() {
            NeighborState sourceState = this.sourcePresent
                    ? NeighborState.sleepSafe(this.source.occupancy())
                    : NeighborState.absent();
            NeighborState destState;
            if (this.unknownDest) {
                destState = NeighborState.unknown();
            } else if (!this.destPresent) {
                destState = NeighborState.absent();
            } else {
                destState = NeighborState.sleepSafe(this.dest.occupancy());
            }
            return new HopperSleepSnapshot(
                    true,
                    this.enabled,
                    this.hopper.cooldown,
                    this.hopper.isEmpty(),
                    this.hopper.isFull(),
                    false,
                    sourceState,
                    destState,
                    false,
                    this.itemEntities > 0);
        }

        boolean sameObservable(final HopperWorld other) {
            return Arrays.equals(this.hopper.items, other.hopper.items)
                    && Arrays.equals(this.dest.items, other.dest.items)
                    && Arrays.equals(this.source.items, other.source.items)
                    && this.hopper.cooldown == other.hopper.cooldown
                    && this.dest.cooldown == other.dest.cooldown
                    && this.itemEntities == other.itemEntities
                    && this.enabled == other.enabled
                    && this.hopper.tickedGameTime == other.hopper.tickedGameTime;
        }

        String observe() {
            return "h=" + Arrays.toString(this.hopper.items)
                    + " cd=" + this.hopper.cooldown
                    + " dest=" + Arrays.toString(this.dest.items)
                    + " dcd=" + this.dest.cooldown
                    + " src=" + Arrays.toString(this.source.items)
                    + " items=" + this.itemEntities
                    + " on=" + this.enabled
                    + " t=" + this.hopper.tickedGameTime
                    + " sleep=" + this.controller.state();
        }
    }

    private static final class Hopper {
        private final int[] items = new int[5];
        private int cooldown;
        private long tickedGameTime;

        boolean isEmpty() {
            for (int item : this.items) {
                if (item > 0) {
                    return false;
                }
            }
            return true;
        }

        boolean isFull() {
            for (int item : this.items) {
                if (item < 64) {
                    return false;
                }
            }
            return true;
        }

        Occupancy occupancy() {
            if (this.isEmpty()) {
                return Occupancy.EMPTY;
            }
            return this.isFull() ? Occupancy.FULL : Occupancy.PARTIAL;
        }
    }
}
