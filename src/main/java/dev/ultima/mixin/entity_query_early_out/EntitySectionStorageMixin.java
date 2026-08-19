package dev.ultima.mixin.entity_query_early_out;

import dev.ultima.entityquery.EntityQueryEarlyOut;
import dev.ultima.entityquery.EntityQueryKind;
import dev.ultima.entityquery.EntitySectionCounterHolder;
import dev.ultima.entityquery.EntitySectionCounters;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Empty-only early-out at {@link EntitySectionStorage#getEntities}. When any intersecting
 * accessible section might contain a relevant entity, this mixin does nothing and vanilla
 * (plus {@code entity_section_lookup}, if enabled) runs unchanged.
 *
 * <p>Does not inject into {@code forEachAccessibleNonEmptySection}; that remains the
 * {@code entity_section_lookup} integration point.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private Long2ObjectMap<EntitySection<T>> sections;

    @Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V", at = @At("HEAD"), cancellable = true)
    private void ultimaEmptyEarlyOutAll(final AABB bb, final AbortableIterationConsumer<T> output, final CallbackInfo ci) {
        if (ultimaAllEmpty(bb, EntityQueryKind.ANY)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private <U extends T> void ultimaEmptyEarlyOutTyped(
            final EntityTypeTest<T, U> type, final AABB bb, final AbortableIterationConsumer<U> consumer, final CallbackInfo ci) {
        EntityQueryKind kind = EntityQueryKind.ofQueryClass(type.getBaseClass());
        if (ultimaAllEmpty(bb, kind)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean ultimaAllEmpty(final AABB bb, final EntityQueryKind kind) {
        return EntityQueryEarlyOut.allIntersectingEmpty(
                EntityQueryEarlyOut.xMin(bb),
                EntityQueryEarlyOut.yMin(bb),
                EntityQueryEarlyOut.zMin(bb),
                EntityQueryEarlyOut.xMax(bb),
                EntityQueryEarlyOut.yMax(bb),
                EntityQueryEarlyOut.zMax(bb),
                kind,
                this::ultimaCountersIfAccessible);
    }

    @Unique
    private EntitySectionCounters ultimaCountersIfAccessible(final long sectionKey) {
        EntitySection<T> section = this.sections.get(sectionKey);
        if (section == null || !section.getStatus().isAccessible()) {
            return null;
        }
        if (section.isEmpty()) {
            return null;
        }
        if (section instanceof EntitySectionCounterHolder holder) {
            EntitySectionCounters counters = holder.ultima$entityCounters();
            if (counters.total() == 0 && !counters.unknown()) {
                EntitySectionCounters desynced = new EntitySectionCounters();
                desynced.add(false, false, false, true);
                return desynced;
            }
            return counters;
        }
        EntitySectionCounters unknown = new EntitySectionCounters();
        unknown.add(false, false, false, true);
        return unknown;
    }
}
