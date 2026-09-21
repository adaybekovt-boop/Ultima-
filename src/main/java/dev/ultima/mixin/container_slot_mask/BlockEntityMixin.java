package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches in-place {@code ItemStack} grow/shrink that vanilla publishes through
 * {@code BlockEntity.setChanged}, including the static overload used by furnaces and hoppers.
 *
 * <p>The instance overload invalidates at {@code HEAD}. Publishing the conservative mutation
 * before vanilla runs is safe even when vanilla throws, and avoids moving the method body around
 * the independent block-entity sleeping injection. The static overload still requires a
 * {@code try/finally} wrapper because it discovers the affected container through the level.
 *
 * <p>{@code applyComponents} is the only public entry to {@code applyImplicitComponents}.
 * Vanilla 26.2 copies item-form contents with {@code ItemContainerContents.copyInto} onto the
 * existing list — it does not call {@code setItems}. {@code loadWithComponents} /
 * {@code loadCustomOnly} run {@code loadAdditional}, which uses {@code ContainerHelper.loadAllItems}
 * the same way. Both wraps invalidate after vanilla, including on throw.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void ultimaInstanceSetChanged(final CallbackInfo ci) {
        if ((Object)this instanceof Container container) {
            SlotMaskHooks.afterSetChanged(container);
        }
    }

    @WrapMethod(method = "applyComponents")
    private void ultimaApplyComponents(
            final DataComponentMap prototype,
            final DataComponentPatch patch,
            final Operation<Void> original) {
        SlotMaskHooks.runInvalidateIfContainer(this, () -> original.call(prototype, patch));
    }

    @WrapMethod(method = "loadWithComponents")
    private void ultimaLoadWithComponents(final ValueInput input, final Operation<Void> original) {
        SlotMaskHooks.runInvalidateIfContainer(this, () -> original.call(input));
    }

    @WrapMethod(method = "loadCustomOnly")
    private void ultimaLoadCustomOnly(final ValueInput input, final Operation<Void> original) {
        SlotMaskHooks.runInvalidateIfContainer(this, () -> original.call(input));
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
