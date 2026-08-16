package dev.ultima.meshing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical mesh equivalence for later hardware-backed tessellation dumps.
 *
 * <p>Rule:
 * <ol>
 *   <li>Per-layer ordered vertices are compared first (exact floats / packed ints).</li>
 *   <li>If order differs, vertices are compared as a multiset per layer. This
 *       covers equivalent rendering when emit order changes but attributes do
 *       not. Winding of each quad is <em>not</em> discarded: quads remain groups
 *       of 4 consecutive vertices.</li>
 *   <li>If quad grouping also differs, the meshes are not equivalent. Ultima
 *       does not claim visual parity from a mere vertex bag.</li>
 * </ol>
 *
 * Production {@code data_mesher} uses vanilla visit order, so rule 1 is the
 * expected path. Rule 2 exists for converters that restrip without changing quads.
 */
public final class MeshEquivalence {
    public enum Result {
        IDENTICAL_ORDERED,
        EQUIVALENT_QUAD_MULTISET,
        DIFFERENT
    }

    public record TerrainVertex(
            int layer,
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int lightU,
            int lightV) implements Comparable<TerrainVertex> {
        @Override
        public int compareTo(final TerrainVertex other) {
            int cmp = Integer.compare(this.layer, other.layer);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Float.compare(this.x, other.x);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Float.compare(this.y, other.y);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Float.compare(this.z, other.z);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(this.color, other.color);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Float.compare(this.u, other.u);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Float.compare(this.v, other.v);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(this.lightU, other.lightU);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(this.lightV, other.lightV);
        }
    }

    private MeshEquivalence() {
    }

    public static Result compare(final List<TerrainVertex> expected, final List<TerrainVertex> actual) {
        if (expected.equals(actual)) {
            return Result.IDENTICAL_ORDERED;
        }
        if (expected.size() != actual.size() || expected.size() % 4 != 0) {
            return Result.DIFFERENT;
        }
        List<QuadKey> expectedQuads = quadKeys(expected);
        List<QuadKey> actualQuads = quadKeys(actual);
        expectedQuads.sort(Comparator.naturalOrder());
        actualQuads.sort(Comparator.naturalOrder());
        return expectedQuads.equals(actualQuads) ? Result.EQUIVALENT_QUAD_MULTISET : Result.DIFFERENT;
    }

    private static List<QuadKey> quadKeys(final List<TerrainVertex> vertices) {
        List<QuadKey> quads = new ArrayList<>(vertices.size() / 4);
        for (int i = 0; i < vertices.size(); i += 4) {
            quads.add(new QuadKey(
                    vertices.get(i),
                    vertices.get(i + 1),
                    vertices.get(i + 2),
                    vertices.get(i + 3)));
        }
        return quads;
    }

    private record QuadKey(TerrainVertex a, TerrainVertex b, TerrainVertex c, TerrainVertex d)
            implements Comparable<QuadKey> {
        @Override
        public int compareTo(final QuadKey other) {
            int cmp = this.a.compareTo(other.a);
            if (cmp != 0) {
                return cmp;
            }
            cmp = this.b.compareTo(other.b);
            if (cmp != 0) {
                return cmp;
            }
            cmp = this.c.compareTo(other.c);
            if (cmp != 0) {
                return cmp;
            }
            return this.d.compareTo(other.d);
        }
    }
}
