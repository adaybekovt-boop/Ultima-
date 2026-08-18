package dev.ultima.entityquery;

/**
 * Mutable counters attached to one {@code EntitySection}. Add/remove are synchronous with vanilla
 * section membership, so a query in the same tick cannot observe a stale section after a move.
 */
public final class EntitySectionCounters implements SectionTypeCounters {
    private int players;
    private int living;
    private int collidable;
    private int items;
    private boolean dirty;

    @Override
    public int players() {
        return this.players;
    }

    @Override
    public int living() {
        return this.living;
    }

    @Override
    public int collidable() {
        return this.collidable;
    }

    @Override
    public int items() {
        return this.items;
    }

    @Override
    public boolean dirty() {
        return this.dirty;
    }

    public void add(final CountedEntityKind kind) {
        this.apply(kind, 1);
    }

    public void remove(final CountedEntityKind kind) {
        this.apply(kind, -1);
    }

    private void apply(final CountedEntityKind kind, final int delta) {
        if (kind.player()) {
            this.players = bump(this.players, delta);
        }
        if (kind.living()) {
            this.living = bump(this.living, delta);
        }
        if (kind.collidable()) {
            this.collidable = bump(this.collidable, delta);
        }
        if (kind.item()) {
            this.items = bump(this.items, delta);
        }
        if (this.players < 0 || this.living < 0 || this.collidable < 0 || this.items < 0) {
            this.players = Math.max(0, this.players);
            this.living = Math.max(0, this.living);
            this.collidable = Math.max(0, this.collidable);
            this.items = Math.max(0, this.items);
            this.dirty = true;
        }
    }

    private static int bump(final int value, final int delta) {
        return value + delta;
    }

    /**
     * Type-level classification. Predicate state (spectator, invisibility, {@code isPickable}
     * flipping) is ignored — those stay on the vanilla path whenever a counter is non-zero.
     */
    public record CountedEntityKind(boolean player, boolean living, boolean collidable, boolean item) {
        public static final CountedEntityKind UNKNOWN_CONSERVATIVE = new CountedEntityKind(true, true, true, true);

        public static CountedEntityKind ofLabels(
                final boolean player, final boolean living, final boolean item, final boolean neverCollidable) {
            boolean collidable = !neverCollidable;
            return new CountedEntityKind(player, living || player, collidable, item);
        }
    }
}
