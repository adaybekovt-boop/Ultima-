package dev.ultima.retained;

/**
 * ViewArea ring identity. {@code RenderSection.index} is reused when the ring
 * wraps; {@code sectionNode} is the world identity. A node change is unload+load
 * of that slot: previous layer commands must be removed before the new identity
 * starts clean.
 */
public final class SectionSlotIdentity {
    private SectionSlotIdentity() {
    }

    public static boolean mustReset(final long storedSectionNode, final long incomingSectionNode) {
        return storedSectionNode != incomingSectionNode;
    }
}
