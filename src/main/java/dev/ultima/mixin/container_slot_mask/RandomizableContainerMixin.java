package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {
    @Inject(method = "unpackLootTable", at = @At("RETURN"))
    private void ultimaAfterUnpack(final Player player, final CallbackInfo ci) {
        if (this instanceof Container container) {
            SlotMaskHooks.invalidate(container);
        }
    }
}
