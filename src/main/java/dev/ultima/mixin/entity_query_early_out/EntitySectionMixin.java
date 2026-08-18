package dev.ultima.mixin.entity_query_early_out;

import dev.ultima.entityquery.EntityQueryClassifier;
import dev.ultima.entityquery.EntitySectionCounters;
import dev.ultima.entityquery.SectionCounterAccess;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements SectionCounterAccess {
    @Unique
    private final EntitySectionCounters ultima$counters = new EntitySectionCounters();

    @Override
    public EntitySectionCounters ultima$counters() {
        return this.ultima$counters;
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void ultimaAdd(final T entity, final CallbackInfo ci) {
        this.ultima$counters.add(EntityQueryClassifier.kindOf(entity));
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void ultimaRemove(final T entity, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            this.ultima$counters.remove(EntityQueryClassifier.kindOf(entity));
        }
    }
}
