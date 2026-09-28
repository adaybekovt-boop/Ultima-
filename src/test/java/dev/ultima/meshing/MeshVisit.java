package dev.ultima.meshing;

/**
 * One interior cell vanilla {@code SectionCompiler} would consider after the
 * air skip, plus the six axis-aligned neighbor state ids it would read for
 * occlusion / fluid / AO at that position.
 */
public record MeshVisit(
        int localX,
        int localY,
        int localZ,
        int worldX,
        int worldY,
        int worldZ,
        int stateId,
        long defaultSeed,
        int flags,
        int neighborNegX,
        int neighborPosX,
        int neighborNegY,
        int neighborPosY,
        int neighborNegZ,
        int neighborPosZ,
        int blockEntitySlot) {
}
