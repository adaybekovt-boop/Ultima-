package dev.ultima.ext;

/**
 * Implemented by {@code Cursor3D} so a block collision query can restrict the cursor to the interior
 * of its volume once it has established that the surrounding shell cannot contribute anything.
 */
public interface InteriorOnlyCursor {
    /**
     * @return whether this cursor can safely honor an interior-only request without changing
     *         vanilla's degenerate or wrapped-index behavior.
     */
    boolean ultimaCanVisitInteriorOnly();

    void ultimaVisitInteriorOnly();
}
