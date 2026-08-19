package dev.ultima.mixin.blockentity_sleeping;

import dev.ultima.sleeping.BlockEntitySleepRuntime;
import dev.ultima.sleeping.HopperSleepFailOpen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {
    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaWakeOnSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
        } catch (Throwable error) {
            BlockEntity self = (BlockEntity)(Object)this;
            HopperSleepFailOpen.failOpen(
                    self.getBlockPos(),
                    self instanceof HopperBlockEntity hopper
                            ? BlockEntitySleepRuntime.controller(hopper)
                            : null,
                    error);
        }
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaWakeOnRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
        } catch (Throwable error) {
            BlockEntity self = (BlockEntity)(Object)this;
            HopperSleepFailOpen.failOpen(
                    self.getBlockPos(),
                    self instanceof HopperBlockEntity hopper
                            ? BlockEntitySleepRuntime.controller(hopper)
                            : null,
                    error);
        }
    }

    @Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
    private void ultimaWakeOnRemoveItemNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
        } catch (Throwable error) {
            BlockEntity self = (BlockEntity)(Object)this;
            HopperSleepFailOpen.failOpen(
                    self.getBlockPos(),
                    self instanceof HopperBlockEntity hopper
                            ? BlockEntitySleepRuntime.controller(hopper)
                            : null,
                    error);
        }
    }

    @Inject(method = "clearContent", at = @At("RETURN"))
    private void ultimaWakeOnClear(final CallbackInfo ci) {
        try {
            BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
        } catch (Throwable error) {
            BlockEntity self = (BlockEntity)(Object)this;
            HopperSleepFailOpen.failOpen(
                    self.getBlockPos(),
                    self instanceof HopperBlockEntity hopper
                            ? BlockEntitySleepRuntime.controller(hopper)
                            : null,
                    error);
        }
    }
}
