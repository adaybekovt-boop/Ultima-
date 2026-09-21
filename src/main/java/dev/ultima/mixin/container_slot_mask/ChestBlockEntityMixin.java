package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Covers vanilla's only direct replacement of a BaseContainerBlockEntity item-list reference. */
@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @WrapMethod(method = "swapContents")
    private static void ultimaSwapContents(
            final ChestBlockEntity one,
            final ChestBlockEntity two,
            final Operation<Void> original) {
        try {
            original.call(one, two);
        } finally {
            SlotMaskHooks.invalidate(one);
            SlotMaskHooks.invalidate(two);
        }
    }
}
