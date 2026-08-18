package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {
    @Inject(method = "isChestVehicleEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof SlotMaskHolder holder) {
            cir.setReturnValue(SlotMaskScan.isEmpty((Container)this, holder.ultima$slotMask()));
        }
    }
}
