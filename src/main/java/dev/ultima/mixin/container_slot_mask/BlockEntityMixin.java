package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Catches in-place {@code ItemStack} grow/shrink that vanilla publishes through
 * {@code BlockEntity.setChanged}, including the static overload used by furnaces and hoppers.
 *
 * <p>Both overloads are {@code @WrapMethod} so the mask is still marked unindexed when
 * vanilla throws before {@code RETURN}.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @WrapMethod(method = "setChanged()V")
    private void ultimaInstanceSetChanged(final Operation<Void> original) {
        Object self = this;
        if (self instanceof Container container) {
            SlotMaskHooks.runUnindexedMutation(container, original::call);
            return;
        }
        original.call();
    }

    @WrapMethod(
            method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V")
    private static void ultimaStaticSetChanged(
            final Level level,
            final BlockPos worldPosition,
            final BlockState blockState,
            final Operation<Void> original) {
        try {
            original.call(level, worldPosition, blockState);
        } finally {
            if (level == null) {
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(worldPosition);
            if (blockEntity instanceof Container container) {
                SlotMaskHooks.afterSetChanged(container);
            }
        }
    }
}
