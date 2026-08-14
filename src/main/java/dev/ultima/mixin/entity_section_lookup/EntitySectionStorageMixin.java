package dev.ultima.mixin.entity_section_lookup;

import dev.ultima.util.SectionRangeMath;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    /** Broad direct probes can be much worse than vanilla in an empty x strip. */
    @Unique
    private static final long ULTIMA_DIRECT_LOOKUP_BUDGET = 1024L;

    @Shadow
    @Final
    private Long2ObjectMap<EntitySection<T>> sections;

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

        /*
         * SectionPos truncates coordinates to 22/20/22 bits. Vanilla decodes z and y before
         * applying the range filter, whereas a direct lookup outside that representable range
         * would alias an unrelated loaded section and return it. Keep vanilla for such inputs.
         */
        if (!SectionRangeMath.isPackable(xMin, yMin, zMin, xMax, yMax, zMax)) {
            return;
        }

        // Saturation is required: the three spans can have a mathematical product up to 2^96.
        long candidates = SectionRangeMath.saturatedVolume(xMin, yMin, zMin, xMax, yMax, zMax);
        if (candidates > ULTIMA_DIRECT_LOOKUP_BUDGET) {
            return;
        }

        ci.cancel();

        for (long xCursor = xMin; xCursor <= xMax; xCursor++) {
            int x = (int)xCursor;
            // Both z and y are masked into the key, so a negative coordinate sorts above every
            // non-negative one. Visiting the non-negative half first reproduces the tree order.
            for (int zHalf = 0; zHalf < 2; zHalf++) {
                int zFrom = zHalf == 0 ? Math.max(zMin, 0) : zMin;
                int zTo = zHalf == 0 ? zMax : Math.min(zMax, -1);

                for (long zCursor = zFrom; zCursor <= zTo; zCursor++) {
                    int z = (int)zCursor;
                    for (int yHalf = 0; yHalf < 2; yHalf++) {
                        int yFrom = yHalf == 0 ? Math.max(yMin, 0) : yMin;
                        int yTo = yHalf == 0 ? yMax : Math.min(yMax, -1);

                        for (long yCursor = yFrom; yCursor <= yTo; yCursor++) {
                            int y = (int)yCursor;
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
