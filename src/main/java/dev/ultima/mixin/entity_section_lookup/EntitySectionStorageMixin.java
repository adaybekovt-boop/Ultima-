package dev.ultima.mixin.entity_section_lookup;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
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
 * <p>Direct probing is only a win while the box covers fewer section keys than the storage holds
 * sections; a wide query against a sparsely populated world would otherwise probe far more keys than
 * vanilla would ever walk, so that case falls back to the vanilla scan.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private Long2ObjectMap<EntitySection<T>> sections;

    @Shadow
    @Final
    private LongSortedSet sectionIds;

    @Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
    private void ultimaLookupSectionsDirectly(final AABB bb, final AbortableIterationConsumer<EntitySection<T>> output, final CallbackInfo ci) {
        int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
        int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
        int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
        int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
        int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
        int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
        if (xMax < xMin || yMax < yMin || zMax < zMin) {
            ci.cancel();
            return;
        }

        // Long arithmetic throughout: a degenerate box can span the whole coordinate range.
        long candidates = ((long)xMax - xMin + 1L) * ((long)yMax - yMin + 1L) * ((long)zMax - zMin + 1L);

        // Probing is only worth it while there are fewer keys to probe than there are sections
        // vanilla could possibly walk. `sectionIds.size()` is the whole storage, so this is a
        // generous bound on vanilla's cost — it never falls back on a query the direct lookup
        // would have won. Without it a wide query against a sparsely populated world probes
        // hundreds of empty keys to find nothing, which is strictly worse than the strip walk.
        if (candidates > this.sectionIds.size()) {
            return;
        }

        ci.cancel();

        for (int x = xMin; x <= xMax; x++) {
            // Both z and y are masked into the key, so a negative coordinate sorts above every
            // non-negative one. Visiting the non-negative half first reproduces the tree order.
            for (int zHalf = 0; zHalf < 2; zHalf++) {
                int zFrom = zHalf == 0 ? Math.max(zMin, 0) : zMin;
                int zTo = zHalf == 0 ? zMax : Math.min(zMax, -1);

                for (int z = zFrom; z <= zTo; z++) {
                    for (int yHalf = 0; yHalf < 2; yHalf++) {
                        int yFrom = yHalf == 0 ? Math.max(yMin, 0) : yMin;
                        int yTo = yHalf == 0 ? yMax : Math.min(yMax, -1);

                        for (int y = yFrom; y <= yTo; y++) {
                            EntitySection<T> section = this.sections.get(SectionPos.asLong(x, y, z));
                            if (section != null
                                    && !section.isEmpty()
                                    && section.getStatus().isAccessible()
                                    && output.accept(section).shouldAbort()) {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
}
