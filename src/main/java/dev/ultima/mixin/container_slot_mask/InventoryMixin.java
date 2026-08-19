package dev.ultima.mixin.container_slot_mask;

import dev.ultima.failopen.FailOpenGuard;
import dev.ultima.inventory.SlotMaskHooks;
import dev.ultima.inventory.SlotMaskQueries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin implements Container {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void ultimaBeforeSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaAfterSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.afterIndexedWrite(this, slot);
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void ultimaBeforeRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite(this);
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
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

    @Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void ultimaAfterIdentityRemove(final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.invalidate(this);
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

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
    private void ultimaAfterAdd(final int slot, final ItemStack itemStack, final CallbackInfoReturnable<Boolean> cir) {
        SlotMaskHooks.invalidate(this);
    }

    @Inject(method = "dropAll", at = @At("RETURN"))
    private void ultimaAfterDropAll(final CallbackInfo ci) {
        SlotMaskHooks.invalidate(this);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void ultimaAfterLoad(final net.minecraft.world.level.storage.ValueInput.TypedInputList<net.minecraft.world.ItemStackWithSlot> input, final CallbackInfo ci) {
        SlotMaskHooks.invalidate(this);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean empty = SlotMaskQueries.tryExactEmpty(this);
            if (empty != null) {
                cir.setReturnValue(empty);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, this, error);
        }
    }
}
