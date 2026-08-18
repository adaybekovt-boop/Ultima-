package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHolder;
import dev.ultima.inventory.SlotMaskScan;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrafterBlockEntity.class)
public abstract class CrafterBlockEntityMixin {
    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof SlotMaskHolder holder) {
            cir.setReturnValue(SlotMaskScan.isEmpty((CrafterBlockEntity)(Object)this, holder.ultima$slotMask()));
        }
    }
}
