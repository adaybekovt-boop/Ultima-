package dev.ultima.entityquery;

/**
 * Per-section occupancy counters. Incremented on {@code EntitySection.add} and decremented on
 * successful {@code remove}. Over-counting is conservative (blocks the early-out). Under-counting
 * is forbidden and is the reason unknown {@code EntityAccess} types set {@link #unknown}.
 *
 * <p>{@code collidable} equals {@code total}: projectile/collision predicates consult instance
 * state ({@code isPickable}, {@code canBeHitByProjectile}) which can change without a section
 * add/remove. Empty-only early-out for those queries is therefore identical to "the section has
 * no entities".
 */
public final class EntitySectionCounters {
    private int players;
    private int living;
    private int items;
    private int collidable;
    private int total;
    private boolean unknown;

    public void add(final boolean player, final boolean livingEntity, final boolean itemEntity, final boolean unknownType) {
        if (unknownType) {
            this.unknown = true;
        }
        this.total++;
        this.collidable++;
        if (player) {
            this.players++;
        }
        if (livingEntity) {
            this.living++;
        }
        if (itemEntity) {
            this.items++;
        }
    }

    public void remove(final boolean player, final boolean livingEntity, final boolean itemEntity, final boolean unknownType) {
        this.total = saturateDec(this.total);
        this.collidable = saturateDec(this.collidable);
        if (player) {
            this.players = saturateDec(this.players);
        }
        if (livingEntity) {
            this.living = saturateDec(this.living);
        }
        if (itemEntity) {
            this.items = saturateDec(this.items);
        }
        if (unknownType) {
            this.unknown = this.total > 0;
        }
    }

    public int players() {
        return this.players;
    }

    public int living() {
        return this.living;
    }

    public int items() {
        return this.items;
    }

    public int collidable() {
        return this.collidable;
    }

    public int total() {
        return this.total;
    }

    public boolean unknown() {
        return this.unknown;
    }

    public int of(final EntityQueryKind kind) {
        return switch (kind) {
            case ANY, COLLIDABLE -> this.total;
            case PLAYERS -> this.players;
            case LIVING -> this.living;
            case ITEMS -> this.items;
            case UNKNOWN -> 1;
        };
    }

    /**
     * A section with an unknown entity type can never prove emptiness for a typed query.
     */
    public boolean blocksEmptyEarlyOut() {
        return this.unknown;
    }

    public void moveTo(final EntitySectionCounters destination, final boolean player, final boolean livingEntity, final boolean itemEntity, final boolean unknownType) {
        this.remove(player, livingEntity, itemEntity, unknownType);
        destination.add(player, livingEntity, itemEntity, unknownType);
    }

    private static int saturateDec(final int value) {
        return value > 0 ? value - 1 : 0;
    }
}
