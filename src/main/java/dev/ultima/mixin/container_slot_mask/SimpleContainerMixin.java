package dev.ultima.mixin.container_slot_mask;

import dev.ultima.failopen.FailOpenGuard;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleContainer.class)
public abstract class SimpleContainerMixin implements Container {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void ultimaBeforeSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaAfterSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.afterIndexedWrite(this, slot);
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void ultimaBeforeRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaAfterRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite(this, slot);
    }

    @Inject(method = "removeItemNoUpdate", at = @At("HEAD"))
    private void ultimaBeforeRemoveNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
    private void ultimaAfterRemoveNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite(this, slot);
    }

    @Inject(method = "clearContent", at = @At("HEAD"))
    private void ultimaBeforeClear(final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "clearContent", at = @At("RETURN"))
    private void ultimaAfterClear(final CallbackInfo ci) {
        SlotMaskHooks.afterClearWrapped(this);
    }

    @Inject(method = "setChanged", at = @At("RETURN"))
    private void ultimaAfterSetChanged(final CallbackInfo ci) {
        SlotMaskHooks.afterSetChanged(this);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean empty = dev.ultima.inventory.SlotMaskQueries.tryExactEmpty(this);
            if (empty != null) {
                cir.setReturnValue(empty);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, this, error);
        }
    }

    @Inject(method = "addItem", at = @At("RETURN"))
    private void ultimaAfterAddItem(final ItemStack itemStack, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.invalidate(this);
    }

    @Inject(method = "removeItemType", at = @At("RETURN"))
    private void ultimaAfterRemoveItemType(final net.minecraft.world.item.Item itemType, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.invalidate(this);
    }
}
