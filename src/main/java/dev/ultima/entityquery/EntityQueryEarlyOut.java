package dev.ultima.entityquery;

import dev.ultima.util.SectionRangeMath;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

/**
 * Empty-only early-out decision for entity section queries.
 *
 * <p><b>Integration with {@code entity_section_lookup}:</b> both modules read the same
 * {@code EntitySectionStorage.sections} hash map and use {@link SectionRangeMath} plus vanilla's
 * {@code CHONKY_ENTITY_SEARCH_GRACE}/{@code MAX_NON_CHONKY_ENTITY_SIZE} padding from
 * {@link EntitySectionStorage#forEachAccessibleNonEmptySection}. {@code entity_section_lookup}
 * replaces the AVL strip walk inside that method. This module injects <em>before</em>
 * {@code getEntities} calls that method: if every intersecting accessible section has a zero
 * counter for the query kind, the vanilla walk is skipped. If any counter is positive, or the
 * box is too large / un-packable, control returns to vanilla unchanged (and the lookup mixin may
 * still apply).
 *
 * <p>Lithium replaces the same query and section storage. Both modules auto-disable when Lithium
 * or a fork is loaded; coexistence is not claimed.
 */
public final class EntityQueryEarlyOut {
    /**
     * Same budget as {@code EntitySectionStorageMixin} in {@code entity_section_lookup}: a huge
     * query box is cheaper as vanilla's strip walk than as a dense key probe.
     */
    public static final long SECTION_PROBE_BUDGET = 1024L;

    private EntityQueryEarlyOut() {
    }

    public static int xMin(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.minX - EntitySectionStorage.CHONKY_ENTITY_SEARCH_GRACE);
    }

    public static int yMin(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.minY - EntitySectionStorage.MAX_NON_CHONKY_ENTITY_SIZE);
    }

    public static int zMin(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.minZ - EntitySectionStorage.CHONKY_ENTITY_SEARCH_GRACE);
    }

    public static int xMax(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.maxX + EntitySectionStorage.CHONKY_ENTITY_SEARCH_GRACE);
    }

    public static int yMax(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.maxY + 0.0);
    }

    public static int zMax(final AABB bb) {
        return SectionPos.posToSectionCoord(bb.maxZ + EntitySectionStorage.CHONKY_ENTITY_SEARCH_GRACE);
    }

    /**
     * @return {@code true} only when every intersecting accessible section is proven empty of
     *         {@code kind}. {@code false} means "run vanilla".
     */
    public static boolean allIntersectingEmpty(
            final int xMin,
            final int yMin,
            final int zMin,
            final int xMax,
            final int yMax,
            final int zMax,
            final EntityQueryKind kind,
            final SectionCounterLookup lookup) {
        if (kind == EntityQueryKind.UNKNOWN) {
            return false;
        }
        if (xMax < xMin || yMax < yMin || zMax < zMin) {
            return true;
        }
        if (!SectionRangeMath.isPackable(xMin, yMin, zMin, xMax, yMax, zMax)) {
            return false;
        }
        long volume = SectionRangeMath.saturatedVolume(xMin, yMin, zMin, xMax, yMax, zMax);
        if (volume > SECTION_PROBE_BUDGET) {
            return false;
        }
        for (long x = xMin; x <= xMax; x++) {
            for (long z = zMin; z <= zMax; z++) {
                for (long y = yMin; y <= yMax; y++) {
                    long key = SectionPos.asLong((int)x, (int)y, (int)z);
                    EntitySectionCounters counters = lookup.countersIfAccessible(key);
                    if (counters == null) {
                        continue;
                    }
                    if (counters.blocksEmptyEarlyOut() || counters.of(kind) > 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    public interface SectionCounterLookup {
        /**
         * @return counters for an accessible non-missing section; {@code null} if the section is
         *         absent or not accessible (vanilla would skip it)
         */
        EntitySectionCounters countersIfAccessible(long sectionKey);
    }
}
