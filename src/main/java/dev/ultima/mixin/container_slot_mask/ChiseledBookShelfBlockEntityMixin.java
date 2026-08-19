package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChiseledBookShelfBlockEntity.class)
public abstract class ChiseledBookShelfBlockEntityMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void ultimaBeforeSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite((ChiseledBookShelfBlockEntity)(Object)this);
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaAfterSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.afterIndexedWrite((ChiseledBookShelfBlockEntity)(Object)this, slot);
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void ultimaBeforeRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite((ChiseledBookShelfBlockEntity)(Object)this);
    }

    @Inject(method = "removeItem", at = @At("RETURN"))
    private void ultimaAfterRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite((ChiseledBookShelfBlockEntity)(Object)this, slot);
    }
}
