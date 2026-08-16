package dev.ultima.temporal;

/**
 * What a motion-vector sample actually represents. Camera-only samples must not
 * be used as if an object moved.
 */
public enum MotionVectorSemantic {
    /** No vector is available; do not invent one. */
    UNAVAILABLE,
    /**
     * World transform is identical between frames. Screen-space velocity comes
     * only from camera view/projection/position change. Correct for static terrain.
     */
    STATIC_WORLD_CAMERA_ONLY,
    /**
     * Previous and current object world transforms are both known. Required for
     * moving entities, animated parts, and block entities that translate/rotate.
     */
    OBJECT_TRANSFORM
}
