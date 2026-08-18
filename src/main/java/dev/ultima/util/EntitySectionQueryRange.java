package dev.ultima.util;

import java.util.function.LongPredicate;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Section-index range of {@code EntitySectionStorage.forEachAccessibleNonEmptySection}.
 *
 * <p>Vanilla expands the query AABB by 2 blocks in X/Z and 4 blocks downward before converting
 * to section coordinates so entities larger than one section ("chonky") are not missed. Module
 * {@code entity_section_lookup} and module {@code entity_query_early_out} share this helper so they
 * never disagree on which sections a box covers or in which order packed keys are visited.
 */
public final class EntitySectionQueryRange {
    /**
     * Same probe budget as {@code entity_section_lookup}: a larger box falls back to vanilla
     * rather than doing more hashmap work than the tree walk.
     */
    public static final long DIRECT_LOOKUP_BUDGET = 1024L;

    public final int xMin;
    public final int yMin;
    public final int zMin;
    public final int xMax;
    public final int yMax;
    public final int zMax;

    public EntitySectionQueryRange(
            final int xMin, final int yMin, final int zMin, final int xMax, final int yMax, final int zMax) {
        this.xMin = xMin;
        this.yMin = yMin;
        this.zMin = zMin;
        this.xMax = xMax;
        this.yMax = yMax;
        this.zMax = zMax;
    }

    /**
     * Mirrors {@code EntitySectionStorage.forEachAccessibleNonEmptySection}:
     * {@code minX-2, minY-4, minZ-2} … {@code maxX+2, maxY+0, maxZ+2}.
     */
    public static EntitySectionQueryRange ofVanillaChonkyExpansion(final AABB bb) {
        return new EntitySectionQueryRange(
                SectionPos.posToSectionCoord(bb.minX - 2.0),
                SectionPos.posToSectionCoord(bb.minY - 4.0),
                SectionPos.posToSectionCoord(bb.minZ - 2.0),
                SectionPos.posToSectionCoord(bb.maxX + 2.0),
                SectionPos.posToSectionCoord(bb.maxY + 0.0),
                SectionPos.posToSectionCoord(bb.maxZ + 2.0));
    }

    public boolean inverted() {
        return this.xMax < this.xMin || this.yMax < this.yMin || this.zMax < this.zMin;
    }

    public boolean packable() {
        return SectionRangeMath.isPackable(this.xMin, this.yMin, this.zMin, this.xMax, this.yMax, this.zMax);
    }

    public long volume() {
        return SectionRangeMath.saturatedVolume(this.xMin, this.yMin, this.zMin, this.xMax, this.yMax, this.zMax);
    }

    /**
     * Packed keys in the same order as vanilla's {@code LongAVLTreeSet} subset walk: x ascending,
     * then non-negative z before negative z, then non-negative y before negative y.
     *
     * @param visitor return {@code false} to abort
     * @return {@code false} if the visitor aborted
     */
    public boolean forEachKey(final LongPredicate visitor) {
        for (long xCursor = this.xMin; xCursor <= this.xMax; xCursor++) {
            int x = (int)xCursor;
            for (int zHalf = 0; zHalf < 2; zHalf++) {
                int zFrom = zHalf == 0 ? Math.max(this.zMin, 0) : this.zMin;
                int zTo = zHalf == 0 ? this.zMax : Math.min(this.zMax, -1);
                for (long zCursor = zFrom; zCursor <= zTo; zCursor++) {
                    int z = (int)zCursor;
                    for (int yHalf = 0; yHalf < 2; yHalf++) {
                        int yFrom = yHalf == 0 ? Math.max(this.yMin, 0) : this.yMin;
                        int yTo = yHalf == 0 ? this.yMax : Math.min(this.yMax, -1);
                        for (long yCursor = yFrom; yCursor <= yTo; yCursor++) {
                            if (!visitor.test(SectionPos.asLong(x, (int)yCursor, z))) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}
