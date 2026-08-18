package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ResultContainer.class)
public abstract class ResultContainerMixin implements SlotMaskHolder {
    @Shadow
    @Final
    private NonNullList<ItemStack> itemStacks;

    @Unique
    private NonEmptySlotMask ultima$mask;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ultimaInit(final CallbackInfo ci) {
        this.ultima$mask = new NonEmptySlotMask(this.itemStacks.size());
        SlotMaskAttach.attach(this.itemStacks, this.ultima$mask);
    }

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        if (this.ultima$mask == null) {
            this.ultima$mask = new NonEmptySlotMask(this.itemStacks.size());
            SlotMaskAttach.attach(this.itemStacks, this.ultima$mask);
            this.ultima$mask.markUntrusted();
        }
        return this.ultima$mask;
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(SlotMaskScan.isEmpty((ResultContainer)(Object)this, this.ultima$slotMask()));
    }
}
