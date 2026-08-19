package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHooks;
import dev.ultima.inventory.SlotMaskQueries;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin implements Container {
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

    @Inject(method = "setItems", at = @At("RETURN"))
    private void ultimaAfterSetItems(final NonNullList<ItemStack> items, final CallbackInfo ci) {
        SlotMaskHooks.invalidate(this);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        Boolean empty = SlotMaskQueries.tryExactEmpty(this);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }
}
