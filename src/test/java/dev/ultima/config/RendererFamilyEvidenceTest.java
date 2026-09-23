package dev.ultima.config;

/**
 * Synthetic mod-id and entry-class evidence. An unrecognized id is not a renderer.
 */
public final class RendererFamilyEvidenceTest {
    private RendererFamilyEvidenceTest() {
    }

    public static void main(final String[] args) {
        run();
    }

    public static void run() {
        if (!RendererFamilyEvidence.conflicts(id -> "sodium".equals(id), name -> false)) {
            throw new AssertionError("an exact sodium id must conflict");
        }
        if (!RendererFamilyEvidence.conflicts(id -> "iris".equals(id), name -> false)) {
            throw new AssertionError("an exact iris id must conflict");
        }
        if (!RendererFamilyEvidence.conflicts(id -> "canvas".equals(id), name -> false)) {
            throw new AssertionError("an exact canvas id must conflict");
        }
        if (RendererFamilyEvidence.conflicts(id -> "embeddium".equals(id) || "rubidium".equals(id), name -> false)) {
            throw new AssertionError("an unrecognized fork id must not be treated as a renderer");
        }
        if (!RendererFamilyEvidence.conflicts(id -> false, RendererFamilyEvidence.SODIUM_ENTRY::equals)) {
            throw new AssertionError("the canonical Sodium entry class must conflict");
        }
        if (!RendererFamilyEvidence.conflicts(id -> false, RendererFamilyEvidence.IRIS_ENTRY::equals)) {
            throw new AssertionError("the canonical Iris entry class must conflict");
        }
        if (RendererFamilyEvidence.conflicts(id -> false, name -> false)) {
            throw new AssertionError("no known id and no known class must stay on vanilla");
        }
        System.out.println("Renderer family evidence checks passed.");
    }
}
