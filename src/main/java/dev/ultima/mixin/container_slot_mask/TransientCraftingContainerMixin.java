package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TransientCraftingContainer.class)
public abstract class TransientCraftingContainerMixin implements SlotMaskHolder {
    @Shadow
    @Final
    private NonNullList<ItemStack> items;

    @Unique
    private NonEmptySlotMask ultima$mask;

    @Inject(
            method = "<init>(Lnet/minecraft/world/inventory/AbstractContainerMenu;IILnet/minecraft/core/NonNullList;)V",
            at = @At("RETURN"))
    private void ultimaInit(
            final AbstractContainerMenu menu,
            final int width,
            final int height,
            final NonNullList<ItemStack> items,
            final CallbackInfo ci) {
        this.ultima$mask = new NonEmptySlotMask(this.items.size());
        SlotMaskAttach.attach(this.items, this.ultima$mask);
        this.ultima$mask.markUntrusted();
    }

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        if (this.ultima$mask == null) {
            this.ultima$mask = new NonEmptySlotMask(this.items.size());
            SlotMaskAttach.attach(this.items, this.ultima$mask);
            this.ultima$mask.markUntrusted();
        }
        return this.ultima$mask;
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(SlotMaskScan.isEmpty((TransientCraftingContainer)(Object)this, this.ultima$slotMask()));
    }
}
