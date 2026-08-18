package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleContainer.class)
public abstract class SimpleContainerMixin implements SlotMaskHolder {
    @Shadow
    @Final
    public net.minecraft.core.NonNullList<ItemStack> items;

    @Unique
    private NonEmptySlotMask ultima$mask = new NonEmptySlotMask(0);

    @Inject(method = "<init>(I)V", at = @At("RETURN"))
    private void ultimaInitSize(final int size, final CallbackInfo ci) {
        this.ultima$initMask(size);
    }

    @Inject(method = "<init>([Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void ultimaInitStacks(final ItemStack[] stacks, final CallbackInfo ci) {
        this.ultima$initMask(stacks.length);
        for (int slot = 0; slot < stacks.length; slot++) {
            this.ultima$mask.setOccupancy(slot, stacks[slot] != null && !stacks[slot].isEmpty());
        }
        this.ultima$mask.markUntrusted();
    }

    @Unique
    private void ultima$initMask(final int size) {
        this.ultima$mask = new NonEmptySlotMask(size);
        SlotMaskAttach.attach(this.items, this.ultima$mask);
    }

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        return this.ultima$mask;
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(SlotMaskScan.isEmpty((SimpleContainer)(Object)this, this.ultima$mask));
    }
}
