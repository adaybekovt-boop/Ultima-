package dev.ultima.entityquery;

/**
 * Which per-section counter an empty early-out may consult.
 *
 * <p>{@link #UNKNOWN} means "do not early-out" — always the vanilla query. {@link #COLLIDABLE} is
 * maintained on every section but is not inferred from an arbitrary {@code Class} query: fishing
 * hooks also match item entities, so untyped queries use {@link #ANY}.
 */
public enum EntityQueryKind {
    ANY,
    PLAYERS,
    LIVING,
    ITEMS,
    COLLIDABLE,
    UNKNOWN
}
