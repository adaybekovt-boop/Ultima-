package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskQueries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "getRedstoneSignalFromContainer", at = @At("HEAD"), cancellable = true)
    private static void ultimaEmptyComparator(final Container container, final CallbackInfoReturnable<Integer> cir) {
        Boolean empty = SlotMaskQueries.tryExactEmpty(container);
        if (empty != null && empty) {
            cir.setReturnValue(0);
        }
    }
}
