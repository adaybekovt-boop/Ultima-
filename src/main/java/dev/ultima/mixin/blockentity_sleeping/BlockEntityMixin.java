package dev.ultima.mixin.blockentity_sleeping;

import dev.ultima.sleeping.BlockEntitySleepRuntime;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void ultimaWakeOnSetChanged(final CallbackInfo ci) {
        if (!BlockEntitySleepRuntime.needsWakes()) {
            return;
        }
        if ((Object)this instanceof Container) {
            BlockEntitySleepRuntime.onContainerMutated((BlockEntity)(Object)this);
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void ultimaUnregisterHopper(final CallbackInfo ci) {
        if ((Object)this instanceof HopperBlockEntity hopper) {
            BlockEntitySleepRuntime.remove(hopper);
        }
    }
}
