package dev.ultima.ext;

/**
 * Implemented by {@code Cursor3D} so a block collision query can restrict the cursor to the interior
 * of its volume once it has established that the surrounding shell cannot contribute anything.
 */
public interface InteriorOnlyCursor {
    /**
     * @return whether this cursor is able to honour {@link #ultimaVisitInteriorOnly()}. A cursor
     *         whose volume is degenerate or large enough to wrap its own index keeps the vanilla
     *         traversal, so deciding whether the shell matters would be work with no possible use.
     */
    boolean ultimaCanVisitInteriorOnly();

    void ultimaVisitInteriorOnly();
}
