package dev.ultima.sleeping;

import java.util.IdentityHashMap;

import dev.ultima.sleeping.hopper.HopperSleepPolicy;
import dev.ultima.sleeping.hopper.HopperSleepSnapshot;
import dev.ultima.sleeping.hopper.HopperWorldInspector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin-facing facade. Fast-path guards are cheap when no hopper is sleeping.
 */
public final class BlockEntitySleepRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-be-sleep");
    private static final IdentityHashMap<HopperBlockEntity, SleepController> CONTROLLERS = new IdentityHashMap<>();
    private static final WakeRegistry REGISTRY = new WakeRegistry();

    private BlockEntitySleepRuntime() {
    }

    public static WakeRegistry registry() {
        return REGISTRY;
    }

    public static boolean needsWakes() {
        return REGISTRY.hasWatchers();
    }

    public static SleepController controller(final HopperBlockEntity hopper) {
        synchronized (CONTROLLERS) {
            return CONTROLLERS.computeIfAbsent(hopper, unused -> new SleepController());
        }
    }

    public static void remove(final HopperBlockEntity hopper) {
        SleepController controller;
        synchronized (CONTROLLERS) {
            controller = CONTROLLERS.remove(hopper);
        }
        if (controller != null) {
            controller.detach();
        }
    }

    public static boolean shouldSkipTryMoveItems(final HopperBlockEntity hopper) {
        SleepController controller = existing(hopper);
        if (controller == null || !controller.isSleeping()) {
            return false;
        }
        BlockEntitySleepMetrics.onTickSkipped();
        return true;
    }

    public static void afterTryMoveItems(
            final Level level,
            final BlockPos pos,
            final BlockState state,
            final HopperBlockEntity hopper,
            final boolean changed
    ) {
        if (level.isClientSide()) {
            return;
        }
        SleepController controller = controller(hopper);
        if (changed) {
            controller.recordWork();
            return;
        }
        if (controller.isPinned() || controller.isSleeping()) {
            return;
        }
        HopperSleepSnapshot snapshot = HopperWorldInspector.inspect(level, pos, state, hopper);
        SleepDecision decision = HopperSleepPolicy.evaluate(snapshot);
        controller.tryEnterSleep(
                decision, REGISTRY, level, HopperWorldInspector.watchPositions(pos, state), level.getGameTime());
    }

    public static void wakeHopper(final HopperBlockEntity hopper, final WakeChannel channel) {
        SleepController controller = existing(hopper);
        if (controller == null) {
            return;
        }
        Level level = hopper.getLevel();
        long gameTime = level != null ? level.getGameTime() : 0L;
        SleepState before = controller.state();
        controller.wake(channel, gameTime);
        if (controller.isPinned() && before == SleepState.SLEEPING) {
            LOGGER.warn(
                    "Ultima hopper sleep circuit breaker pinned hopper at {} ({})",
                    hopper.getBlockPos(),
                    controller.lastRefuseReason());
        }
    }

    public static void onBlockChanged(final Level level, final BlockPos pos) {
        if (!needsWakes() || level.isClientSide()) {
            return;
        }
        REGISTRY.wakeAt(level, pos, WakeChannel.BLOCK_UPDATE);
    }

    public static void onContainerMutated(final BlockEntity blockEntity) {
        if (!needsWakes()) {
            return;
        }
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        REGISTRY.wakeAt(level, blockEntity.getBlockPos(), WakeChannel.NEIGHBOR_INVENTORY);
        if (blockEntity instanceof HopperBlockEntity hopper) {
            wakeHopper(hopper, WakeChannel.SELF_INVENTORY);
        }
    }

    public static void onTrackedEntityMoved(final Entity entity) {
        if (!needsWakes()) {
            return;
        }
        Level level = entity.level();
        if (level.isClientSide()) {
            return;
        }
        WakeChannel channel = entity instanceof ItemEntity ? WakeChannel.ITEM_ENTITY : WakeChannel.CONTAINER_ENTITY;
        BlockPos pos = entity.blockPosition();
        REGISTRY.wakeAt(level, pos, channel);
        REGISTRY.wakeAt(level, pos.below(), channel);
    }

    public static boolean isTrackedEntity(final Entity entity) {
        return entity instanceof ItemEntity || entity instanceof Container;
    }

    private static SleepController existing(final HopperBlockEntity hopper) {
        synchronized (CONTROLLERS) {
            return CONTROLLERS.get(hopper);
        }
    }
}
