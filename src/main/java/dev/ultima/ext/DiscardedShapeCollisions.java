package dev.ultima.ext;

/**
 * Implemented by {@code BlockCollisions} so {@code findSupportingBlock} can mark a query whose
 * result provider discards the voxel shape argument.
 */
public interface DiscardedShapeCollisions {
    void ultimaDiscardReturnedShape();

    boolean ultimaIsReturnedShapeDiscarded();
}
