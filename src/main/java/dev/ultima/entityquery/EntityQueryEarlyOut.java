package dev.ultima.entityquery;

import dev.ultima.util.EntitySectionQueryRange;
import java.util.function.LongFunction;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Empty-only early-out for entity section queries.
 *
 * <p><b>Integration with {@code entity_section_lookup}:</b> both modules address
 * {@code EntitySectionStorage}. Lookup replaces {@code forEachAccessibleNonEmptySection} with
 * direct hashmap probes in vanilla key order. This module injects {@code getEntities} (the typed
 * and untyped funnels used by {@code LevelEntityGetterAdapter}) and reads counters stored on the
 * same {@code EntitySection} instances in that map. Range, chonky AABB expansion, packable
 * checks, probe budget, and visit order come from {@link dev.ultima.util.EntitySectionQueryRange} — there is no
 * second spatial index.
 *
 * <p><b>Lithium:</b> Lithium replaces the same section iterator / entity collection paths
 * ({@code EntitySectionStorage}, often {@code ClassInstanceMultiMap} grouping). Coexistence is
 * not proven; both this module and {@code entity_section_lookup} auto-disable when Lithium,
 * Canary, or Radium is loaded.
 *
 * <p>If any intersecting accessible section has a relevant counter &gt; 0, this returns
 * {@code false} and the caller must invoke vanilla unchanged — including sections whose counters
 * are zero. Early-out is all-or-nothing at query granularity.
 */
public final class EntityQueryEarlyOut {
    private EntityQueryEarlyOut() {
    }

    @FunctionalInterface
    public interface SectionView {
        @Nullable View get(long sectionKey);
    }

    public record View(boolean empty, boolean accessible, SectionTypeCounters counters) {
    }

    /**
     * @return {@code true} only when vanilla would visit no relevant entities (empty result from
     *         section storage). {@code ExperienceOrb} attraction in 26.2 uses
     *         {@code getNearestPlayer} over the level player list, not this section walk.
     *         Dragon parts added later by {@code Level.getEntities} are out of
     *         scope of this method and remain vanilla.
     */
    public static boolean proveEmpty(
            final SectionView sections, final AABB bb, final EntityQueryKind kind) {
        if (kind == EntityQueryKind.UNKNOWN) {
            return false;
        }
        EntitySectionQueryRange range = EntitySectionQueryRange.ofVanillaChonkyExpansion(bb);
        return proveEmpty(sections, range, kind);
    }

    public static boolean proveEmpty(
            final SectionView sections, final EntitySectionQueryRange range, final EntityQueryKind kind) {
        if (kind == EntityQueryKind.UNKNOWN) {
            return false;
        }
        if (range.inverted()) {
            return true;
        }
        if (!range.packable()) {
            return false;
        }
        long volume = range.volume();
        if (volume > EntitySectionQueryRange.DIRECT_LOOKUP_BUDGET) {
            return false;
        }
        boolean[] candidate = {false};
        range.forEachKey(key -> {
            View section = sections.get(key);
            if (section == null || section.empty() || !section.accessible()) {
                return true;
            }
            if (kind == EntityQueryKind.ANY || section.counters().has(kind)) {
                // ANY: vanilla visits every accessible non-empty section. A non-empty
                // section is a candidate even if type counters were somehow zero.
                candidate[0] = true;
                return false;
            }
            return true;
        });
        return !candidate[0];
    }

    public static SectionView mapLookup(final LongFunction<@Nullable View> lookup) {
        return lookup::apply;
    }
}
