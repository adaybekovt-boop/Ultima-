package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Comparator occupancy scan. Empty slots contribute 0 to the fill ratio, so iterating only
 * non-empty hint bits is equivalent when the mask does not miss a non-empty slot. First-use /
 * periodic verification rebuilds from a full scan.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "getRedstoneSignalFromContainer", at = @At("HEAD"), cancellable = true)
    private static void ultimaSlotMaskSignal(
            final Container container, final CallbackInfoReturnable<Integer> cir) {
        if (!(container instanceof SlotMaskHolder holder)) {
            return;
        }
        NonEmptySlotMask mask = holder.ultima$slotMask();
        Integer signal = SlotMaskScan.redstoneSignal(container, mask);
        if (signal != null) {
            cir.setReturnValue(signal);
        }
    }
}
