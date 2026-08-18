package dev.ultima.mixin.entity_section_lookup;

import dev.ultima.util.EntitySectionQueryRange;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity section keys pack x into the high bits, z into the middle bits and y into the low bits, so
 * the {@code sectionIds} tree range vanilla queries for one x coordinate spans <em>every</em> z and
 * y of that coordinate. Vanilla therefore walks every non-empty entity section in a whole chunk
 * strip and filters it down with an {@code if}, which makes answering a query about a single
 * entity's bounding box proportional to how much of the world is loaded.
 *
 * <p>The sections intersecting the box are known up front, so they are looked up directly in the
 * hash map instead. The visited set is identical because {@code sections} and {@code sectionIds}
 * always hold the same keys, and the sections are visited in exactly the vanilla order so that any
 * ordering the caller can observe is unchanged.
 *
 * <p>AABB expansion, packable checks, the 1024-probe budget, and visit order are
 * {@link EntitySectionQueryRange} — the same helper {@code entity_query_early_out} uses to decide
 * empty early-outs, so the two modules never disagree on which sections a box covers.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private Long2ObjectMap<EntitySection<T>> sections;

    @Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
    private void ultimaLookupSectionsDirectly(final AABB bb, final AbortableIterationConsumer<EntitySection<T>> output, final CallbackInfo ci) {
        EntitySectionQueryRange range = EntitySectionQueryRange.ofVanillaChonkyExpansion(bb);
        if (range.inverted()) {
            ci.cancel();
            return;
        }

        /*
         * SectionPos truncates coordinates to 22/20/22 bits. Vanilla decodes z and y before
         * applying the range filter, whereas a direct lookup outside that representable range
         * would alias an unrelated loaded section and return it. Keep vanilla for such inputs.
         */
        if (!range.packable()) {
            return;
        }

        // Saturation is required: the three spans can have a mathematical product up to 2^96.
        long candidates = range.volume();
        if (candidates > EntitySectionQueryRange.DIRECT_LOOKUP_BUDGET || candidates > this.sections.size()) {
            return;
        }

        ci.cancel();
        range.forEachKey(key -> {
            EntitySection<T> section = this.sections.get(key);
            return section == null
                    || section.isEmpty()
                    || !section.getStatus().isAccessible()
                    || !output.accept(section).shouldAbort();
        });
    }
}
