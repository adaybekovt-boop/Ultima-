package dev.ultima.mixin.entity_query_early_out;

import dev.ultima.entityquery.EntitySectionCounterHolder;
import dev.ultima.entityquery.EntitySectionCounters;
import dev.ultima.entityquery.EntitySectionOccupant;
import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Maintains per-section occupancy counters used by {@code entity_query_early_out}.
 * Add/remove are the same methods {@code PersistentEntitySectionManager.Callback#onMove} uses,
 * so a same-tick section transfer updates counters before any later query on this thread.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements EntitySectionCounterHolder {
    @Unique
    private final EntitySectionCounters ultima$counters = new EntitySectionCounters();

    @Override
    public EntitySectionCounters ultima$entityCounters() {
        return this.ultima$counters;
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void ultimaAfterAdd(final T entity, final CallbackInfo ci) {
        try {
            EntitySectionOccupant.of(entity).addTo(this.ultima$counters);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.ENTITY_QUERY_EARLY_OUT, "section-add", error);
            this.ultima$counters.add(false, false, false, true);
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void ultimaAfterRemove(final T entity, final CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Boolean.TRUE.equals(cir.getReturnValue())) {
                EntitySectionOccupant.of(entity).removeFrom(this.ultima$counters);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.ENTITY_QUERY_EARLY_OUT, "section-remove", error);
            this.ultima$counters.add(false, false, false, true);
        }
    }
}
