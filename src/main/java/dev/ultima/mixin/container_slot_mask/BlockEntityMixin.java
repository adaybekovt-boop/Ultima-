package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches in-place {@code ItemStack} grow/shrink that vanilla publishes through
 * {@code BlockEntity.setChanged}, including the static overload used by furnaces and hoppers.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void ultimaAfterInstanceSetChanged(final CallbackInfo ci) {
        Object self = this;
        if (self instanceof Container container) {
            SlotMaskHooks.afterSetChanged(container);
        }
    }

    @Inject(
            method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("RETURN"))
    private static void ultimaAfterStaticSetChanged(
            final Level level, final BlockPos worldPosition, final BlockState blockState, final CallbackInfo ci) {
        if (level == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(worldPosition);
        if (blockEntity instanceof Container container) {
            SlotMaskHooks.afterSetChanged(container);
        }
    }
}
