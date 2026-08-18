package dev.ultima.entityquery;

/**
 * Per-section type counters used solely to prove "this query box contains no candidates".
 *
 * <p>When any relevant counter is non-zero (or {@link #dirty()}), the caller must run the vanilla
 * query unchanged — never a filtered candidate list.
 */
public interface SectionTypeCounters {
    int players();

    int living();

    int collidable();

    int items();

    boolean dirty();

    default boolean has(final EntityQueryKind kind) {
        if (this.dirty()) {
            return true;
        }
        return switch (kind) {
            case ANY -> this.players() > 0 || this.living() > 0 || this.collidable() > 0 || this.items() > 0;
            case PLAYERS -> this.players() > 0;
            case LIVING -> this.living() > 0;
            case ITEMS -> this.items() > 0;
            case COLLIDABLE -> this.collidable() > 0;
            case UNKNOWN -> true;
        };
    }
}
