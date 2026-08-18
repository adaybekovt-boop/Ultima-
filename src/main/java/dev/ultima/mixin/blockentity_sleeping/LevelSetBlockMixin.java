package dev.ultima.mixin.blockentity_sleeping;

import dev.ultima.sleeping.BlockEntitySleepRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelSetBlockMixin {
    @Shadow
    public abstract boolean isClientSide();

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void ultimaWakeSleepingHoppers(
            final BlockPos pos,
            final BlockState state,
            final int updateFlags,
            final int updateLimit,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || this.isClientSide() || !BlockEntitySleepRuntime.needsWakes()) {
            return;
        }
        BlockEntitySleepRuntime.onBlockChanged((Level)(Object)this, pos);
    }
}
