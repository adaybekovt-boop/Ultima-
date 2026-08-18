package dev.ultima.entityquery;

/**
 * Implemented by {@code EntitySectionMixin} so {@code entity_query_early_out}'s
 * {@code EntitySectionStorage} mixin can read counters without a parallel index.
 */
public interface EntitySectionCounterHolder {
    EntitySectionCounters ultima$entityCounters();
}
