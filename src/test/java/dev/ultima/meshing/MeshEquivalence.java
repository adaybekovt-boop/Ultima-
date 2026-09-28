package dev.ultima.meshing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical mesh equivalence for fast-path vs vanilla-oracle vertex dumps.
 *
 * <p>Rule:
 * <ol>
 *   <li>Per-layer ordered vertices are compared first (exact floats / packed ints).</li>
 *   <li>If order differs, vertices are compared as a multiset per layer. Quads
 *       remain groups of 4 consecutive vertices; winding is not discarded.</li>
 *   <li>If quad grouping also differs, the meshes are not equivalent.</li>
 * </ol>
 *
 * Float epsilon is used only for AO/lighting-derived color channels that go
 * through {@code ARGB.gray}. Positions, UVs, topology, and packed light must
 * match exactly.
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
            int light) implements Comparable<TerrainVertex> {
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
            return Integer.compare(this.light, other.light);
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

    public static Result compareExactGeometry(final List<TerrainVertex> expected, final List<TerrainVertex> actual) {
        if (expected.size() != actual.size()) {
            return Result.DIFFERENT;
        }
        for (int i = 0; i < expected.size(); i++) {
            TerrainVertex a = expected.get(i);
            TerrainVertex b = actual.get(i);
            if (a.layer != b.layer
                    || a.x != b.x
                    || a.y != b.y
                    || a.z != b.z
                    || a.u != b.u
                    || a.v != b.v
                    || a.light != b.light
                    || a.color != b.color) {
                return Result.DIFFERENT;
            }
        }
        return Result.IDENTICAL_ORDERED;
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
