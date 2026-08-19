package dev.ultima.mixin.blockentity_sleeping;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.sleeping.BlockEntitySleepRuntime;
import dev.ultima.sleeping.HopperSleepFailOpen;
import dev.ultima.sleeping.VanillaContainerClassifier;
import dev.ultima.sleeping.WakeChannel;
import dev.ultima.sleeping.hopper.HopperWorldInspector;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@code tryMoveItems} only while a hopper is proven idle and sleeping. Vanilla still
 * decrements cooldown and writes {@code tickedGameTime} because this wrap does not cancel
 * {@code pushItemsTick}.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    static {
        HopperWorldInspector.HopperCooldownAccess.install(
                hopper -> ((HopperBlockEntityAccessor)hopper).ultima$getCooldownTime());
        HopperWorldInspector.HopperItemAccess.install(
                (hopper, slot) -> ((HopperBlockEntityAccessor)hopper).ultima$getItems().get(slot));
        VanillaContainerClassifier.CompoundContainerAccess.install(new VanillaContainerClassifier.CompoundContainerAccess.Access() {
            @Override
            public Container left(final CompoundContainer compound) {
                return ((CompoundContainerAccessor)compound).ultima$container1();
            }

            @Override
            public Container right(final CompoundContainer compound) {
                return ((CompoundContainerAccessor)compound).ultima$container2();
            }
        });
    }

    @WrapOperation(
            method = {"pushItemsTick", "entityInside"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z"))
    private static boolean ultimaMaybeSkipTryMoveItems(
            final Level level,
            final BlockPos pos,
            final BlockState state,
            final HopperBlockEntity hopper,
            final BooleanSupplier suck,
            final Operation<Boolean> original
    ) {
        boolean skip;
        try {
            skip = BlockEntitySleepRuntime.shouldSkipTryMoveItems(hopper);
        } catch (Throwable error) {
            HopperSleepFailOpen.failOpen(pos, BlockEntitySleepRuntime.controller(hopper), error);
            skip = false;
        }
        if (skip) {
            return false;
        }
        boolean changed = original.call(level, pos, state, hopper, suck);
        try {
            BlockEntitySleepRuntime.afterTryMoveItems(level, pos, state, hopper, changed);
        } catch (Throwable error) {
            HopperSleepFailOpen.failOpen(pos, BlockEntitySleepRuntime.controller(hopper), error);
        }
        return changed;
    }

    @Inject(method = "entityInside", at = @At("HEAD"))
    private static void ultimaWakeOnEntityInside(
            final Level level,
            final BlockPos pos,
            final BlockState state,
            final Entity entity,
            final HopperBlockEntity hopper,
            final CallbackInfo ci
    ) {
        try {
            BlockEntitySleepRuntime.wakeHopper(hopper, WakeChannel.ITEM_ENTITY);
        } catch (Throwable error) {
            HopperSleepFailOpen.failOpen(pos, BlockEntitySleepRuntime.controller(hopper), error);
        }
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaWakeOnSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((HopperBlockEntity)(Object)this);
        } catch (Throwable error) {
            HopperSleepFailOpen.failOpen(
                    ((HopperBlockEntity)(Object)this).getBlockPos(),
                    BlockEntitySleepRuntime.controller((HopperBlockEntity)(Object)this),
                    error);
        }
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaWakeOnRemoveItem(final int slot, final int count, final CallbackInfo ci) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((HopperBlockEntity)(Object)this);
        } catch (Throwable error) {
            HopperSleepFailOpen.failOpen(
                    ((HopperBlockEntity)(Object)this).getBlockPos(),
                    BlockEntitySleepRuntime.controller((HopperBlockEntity)(Object)this),
                    error);
        }
    }
}
