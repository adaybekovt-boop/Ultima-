package dev.ultima.entityquery;

/**
 * Lithium intersection for {@code container_slot_mask} and {@code entity_query_early_out}.
 *
 * <p>Lithium (LGPL-3.0-only) and forks (Canary, Radium) replace hopper inventory tracking
 * ({@code LithiumStackList} occupied/full bitmasks, hopper sleeping) and entity section iteration
 * (class-grouped section storage, typed query skipping). Ultima's implementations are independent
 * code, not a port, but they inject into the same vanilla methods.
 *
 * <p><b>Decision: auto-disable both modules when Lithium/Canary/Radium is loaded.</b>
 * Coexistence is not proven. Dual mixins on {@code EntitySectionStorage.getEntities} and
 * {@code Container.setItem} could compose, but Lithium's replacements make Ultima's section
 * counters and container masks observe a different backing store than vanilla. Empty-only
 * early-outs are only sound against vanilla's query. Fail open.
 */
public final class LithiumIntersection {
    public static final String DECISION = "auto-disable";

    private LithiumIntersection() {
    }
}
