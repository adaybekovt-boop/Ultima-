package dev.ultima.mixin.entity_query_early_out;

import dev.ultima.entityquery.EntityQueryClassifier;
import dev.ultima.entityquery.EntityQueryEarlyOut;
import dev.ultima.entityquery.EntityQueryKind;
import dev.ultima.entityquery.SectionCounterAccess;
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
 * Empty-only gate in front of vanilla {@code getEntities}. When the query box's accessible
 * sections have no relevant type counters, the result is identically empty and the vanilla walk
 * is skipped. Otherwise this injection does not cancel — {@code entity_section_lookup} (if on)
 * or vanilla then runs unchanged.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private Long2ObjectMap<EntitySection<T>> sections;

    @Inject(
            method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ultimaEmptyAny(final AABB bb, final AbortableIterationConsumer<T> output, final CallbackInfo ci) {
        if (this.ultimaProveEmpty(bb, EntityQueryKind.ANY)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private <U extends T> void ultimaEmptyTyped(
            final EntityTypeTest<T, U> type,
            final AABB bb,
            final AbortableIterationConsumer<U> consumer,
            final CallbackInfo ci) {
        EntityQueryKind kind = EntityQueryClassifier.classify(type);
        if (this.ultimaProveEmpty(bb, kind)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean ultimaProveEmpty(final AABB bb, final EntityQueryKind kind) {
        return EntityQueryEarlyOut.proveEmpty(
                key -> {
                    EntitySection<T> section = this.sections.get(key);
                    if (section == null) {
                        return null;
                    }
                    return new EntityQueryEarlyOut.View(
                            section.isEmpty(),
                            section.getStatus().isAccessible(),
                            ((SectionCounterAccess)section).ultima$counters());
                },
                bb,
                kind);
    }
}
