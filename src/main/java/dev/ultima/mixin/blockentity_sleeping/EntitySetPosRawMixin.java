package dev.ultima.mixin.blockentity_sleeping;

import dev.ultima.sleeping.BlockEntitySleepRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntitySetPosRawMixin {
    @Inject(method = "setPosRaw", at = @At("RETURN"))
    private void ultimaWakeSleepingHoppers(final double x, final double y, final double z, final CallbackInfo ci) {
        if (!BlockEntitySleepRuntime.needsWakes()) {
            return;
        }
        Entity self = (Entity)(Object)this;
        Level level = self.level();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (BlockEntitySleepRuntime.isTrackedEntity(self)) {
            BlockEntitySleepRuntime.onTrackedEntityMoved(self);
        }
    }
}
