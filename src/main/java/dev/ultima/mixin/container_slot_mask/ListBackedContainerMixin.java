package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ListBackedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ListBackedContainer.class)
public interface ListBackedContainerMixin {
    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        ListBackedContainer self = (ListBackedContainer)this;
        NonNullList<ItemStack> items = self.getItems();
        if (!(items instanceof dev.ultima.inventory.SlotMaskList list)) {
            return;
        }
        NonEmptySlotMask mask = list.ultima$slotMask();
        if (mask == null) {
            mask = new NonEmptySlotMask(items.size());
            SlotMaskAttach.attach(items, mask);
            mask.markUntrusted();
        }
        cir.setReturnValue(SlotMaskScan.isEmpty(self, mask));
    }
}
