package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin implements SlotMaskHolder {
    @Unique
    private NonEmptySlotMask ultima$mask;

    @Shadow
    protected abstract NonNullList<ItemStack> getItems();

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        this.ultima$ensureMask();
        return this.ultima$mask;
    }

    @Unique
    private void ultima$ensureMask() {
        NonNullList<ItemStack> items = this.getItems();
        int size = items.size();
        if (this.ultima$mask == null || this.ultima$mask.slotCount() != size) {
            this.ultima$mask = new NonEmptySlotMask(size);
            this.ultima$mask.markUntrusted();
        }
        if (items instanceof dev.ultima.inventory.SlotMaskList list && list.ultima$slotMask() != this.ultima$mask) {
            this.ultima$mask.markUntrusted();
        }
        SlotMaskAttach.attach(items, this.ultima$mask);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        this.ultima$ensureMask();
        cir.setReturnValue(SlotMaskScan.isEmpty((BaseContainerBlockEntity)(Object)this, this.ultima$mask));
    }
}
