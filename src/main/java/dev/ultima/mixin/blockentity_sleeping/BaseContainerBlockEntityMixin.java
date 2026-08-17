package dev.ultima.mixin.blockentity_sleeping;

import dev.ultima.sleeping.BlockEntitySleepRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {
    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaWakeOnSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaWakeOnRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
    }

    @Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
    private void ultimaWakeOnRemoveItemNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
    }

    @Inject(method = "clearContent", at = @At("RETURN"))
    private void ultimaWakeOnClear(final CallbackInfo ci) {
        BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
    }
}
