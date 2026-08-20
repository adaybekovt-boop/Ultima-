package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {
    @WrapMethod(method = "unpackLootTable")
    default void ultimaUnpackLootTable(final Player player, final Operation<Void> original) {
        if (this instanceof Container container) {
            SlotMaskHooks.runInvalidate(container, () -> original.call(player));
            return;
        }
        original.call(player);
    }
}
