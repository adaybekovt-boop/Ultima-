package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin implements SlotMaskHolder {
    @Shadow
    @Final
    private NonNullList<ItemStack> items;

    @Shadow
    public abstract int getContainerSize();

    @Unique
    private NonEmptySlotMask ultima$mask;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ultimaInit(final CallbackInfo ci) {
        this.ultima$mask = new NonEmptySlotMask(this.getContainerSize());
        SlotMaskAttach.attach(this.items, this.ultima$mask);
    }

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        if (this.ultima$mask == null) {
            this.ultima$mask = new NonEmptySlotMask(this.getContainerSize());
            SlotMaskAttach.attach(this.items, this.ultima$mask);
            this.ultima$mask.markUntrusted();
        }
        return this.ultima$mask;
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void ultimaSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        if (this.ultima$mask != null && slot >= this.items.size()) {
            this.ultima$mask.setOccupancy(slot, !itemStack.isEmpty());
        }
    }

    @Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
    private void ultimaRemoveNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        if (this.ultima$mask != null && slot >= this.items.size()) {
            this.ultima$mask.setOccupancy(slot, false);
        }
    }

    @Inject(method = "clearContent", at = @At("RETURN"))
    private void ultimaClear(final CallbackInfo ci) {
        if (this.ultima$mask != null) {
            this.ultima$mask.clearAll();
        }
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(SlotMaskScan.isEmpty((Inventory)(Object)this, this.ultima$slotMask()));
    }
}
