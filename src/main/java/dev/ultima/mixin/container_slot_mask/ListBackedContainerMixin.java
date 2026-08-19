package dev.ultima.mixin.container_slot_mask;

import dev.ultima.failopen.FailOpenGuard;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ListBackedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ListBackedContainer.class)
public interface ListBackedContainerMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void ultimaBeforeSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaAfterSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.afterIndexedWrite((Container)(Object)this, slot);
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void ultimaBeforeRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaAfterRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite((Container)(Object)this, slot);
    }

    @Inject(method = "clearContent", at = @At("HEAD"))
    private void ultimaBeforeClear(final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "clearContent", at = @At("RETURN"))
    private void ultimaAfterClear(final CallbackInfo ci) {
        SlotMaskHooks.afterClearWrapped((Container)(Object)this);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean empty = dev.ultima.inventory.SlotMaskQueries.tryExactEmpty((Container)(Object)this);
            if (empty != null) {
                cir.setReturnValue(empty);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, this, error);
        }
    }
}
