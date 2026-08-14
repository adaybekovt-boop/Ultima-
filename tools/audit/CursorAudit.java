import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Second-pass audit harness for Ultima's `cursor_step` module.
 *
 * Reproduces vanilla Cursor3D and Ultima's replacement side by side and checks:
 *   1. the divide-free full traversal emits an identical (x,y,z,type) sequence;
 *   2. the interior-only traversal emits exactly the TYPE_INSIDE subsequence, in order;
 *   3. both stay exhausted after finishing;
 *   4. the interior traversal is never a superset (would let an entity phase through a fence)
 *      and never a strict subset of TYPE_INSIDE (would lose a real collider).
 */
public final class CursorAudit {

    /** Faithful port of net.minecraft.core.Cursor3D. */
    static final class Vanilla {
        final int originX, originY, originZ, width, height, depth, end;
        int index, x, y, z;

        Vanilla(int ox, int oy, int oz, int ex, int ey, int ez) {
            this.originX = ox; this.originY = oy; this.originZ = oz;
            this.width = ex - ox + 1;
            this.height = ey - oy + 1;
            this.depth = ez - oz + 1;
            this.end = this.width * this.height * this.depth;
        }

        boolean advance() {
            if (this.index == this.end) return false;
            this.x = this.index % this.width;
            int i = this.index / this.width;
            this.y = i % this.height;
            this.z = i / this.height;
            this.index++;
            return true;
        }

        int nextX() { return this.originX + this.x; }
        int nextY() { return this.originY + this.y; }
        int nextZ() { return this.originZ + this.z; }

        int getNextType() {
            int i = 0;
            if (this.x == 0 || this.x == this.width - 1) i++;
            if (this.y == 0 || this.y == this.height - 1) i++;
            if (this.z == 0 || this.z == this.depth - 1) i++;
            return i;
        }
    }

    /** Ultima's Cursor3DMixin logic, transcribed verbatim from the branch. */
    static final class Ultima {
        final int originX, originY, originZ, width, height, depth, end;
        int index, x, y, z;
        boolean interiorOnly, interiorStarted, interiorExhausted;

        Ultima(int ox, int oy, int oz, int ex, int ey, int ez) {
            this.originX = ox; this.originY = oy; this.originZ = oz;
            this.width = ex - ox + 1;
            this.height = ey - oy + 1;
            this.depth = ez - oz + 1;
            this.end = this.width * this.height * this.depth;
        }

        void ultimaVisitInteriorOnly() { this.interiorOnly = true; }

        boolean advance() {
            if (this.interiorOnly) return advanceInterior();
            if (this.index == this.end) return false;
            if (this.index != 0) {
                if (++this.x == this.width) {
                    this.x = 0;
                    if (++this.y == this.height) {
                        this.y = 0;
                        this.z++;
                    }
                }
            }
            this.index++;
            return true;
        }

        private boolean advanceInterior() {
            if (this.interiorExhausted) return false;
            if (!this.interiorStarted) {
                if (this.width < 3 || this.height < 3 || this.depth < 3) {
                    this.interiorExhausted = true;
                    return false;
                }
                this.interiorStarted = true;
                this.x = 1; this.y = 1; this.z = 1;
                this.index++;
                return true;
            }
            if (++this.x == this.width - 1) {
                this.x = 1;
                if (++this.y == this.height - 1) {
                    this.y = 1;
                    if (++this.z == this.depth - 1) {
                        this.interiorExhausted = true;
                        return false;
                    }
                }
            }
            this.index++;
            return true;
        }

        int nextX() { return this.originX + this.x; }
        int nextY() { return this.originY + this.y; }
        int nextZ() { return this.originZ + this.z; }

        int getNextType() {
            int i = 0;
            if (this.x == 0 || this.x == this.width - 1) i++;
            if (this.y == 0 || this.y == this.height - 1) i++;
            if (this.z == 0 || this.z == this.depth - 1) i++;
            return i;
        }
    }

    record Step(int x, int y, int z, int type) {}

    static List<Step> walkVanilla(int ox, int oy, int oz, int ex, int ey, int ez) {
        Vanilla c = new Vanilla(ox, oy, oz, ex, ey, ez);
        List<Step> out = new ArrayList<>();
        while (c.advance()) out.add(new Step(c.nextX(), c.nextY(), c.nextZ(), c.getNextType()));
        for (int i = 0; i < 3; i++) if (c.advance()) throw new AssertionError("vanilla resurrected");
        return out;
    }

    static List<Step> walkUltima(int ox, int oy, int oz, int ex, int ey, int ez, boolean interior) {
        Ultima c = new Ultima(ox, oy, oz, ex, ey, ez);
        if (interior) c.ultimaVisitInteriorOnly();
        List<Step> out = new ArrayList<>();
        while (c.advance()) out.add(new Step(c.nextX(), c.nextY(), c.nextZ(), c.getNextType()));
        for (int i = 0; i < 3; i++) if (c.advance()) throw new AssertionError("ultima resurrected");
        return out;
    }

    public static void main(String[] args) {
        Random rng = new Random(20260814L);
        long volumes = 0, positions = 0, interiorPositions = 0;
        long fullMismatch = 0, interiorMismatch = 0;

        // Exhaustive small volumes, including every degenerate span.
        List<int[]> cases = new ArrayList<>();
        for (int w = 1; w <= 6; w++)
            for (int h = 1; h <= 6; h++)
                for (int d = 1; d <= 6; d++)
                    cases.add(new int[]{w, h, d});
        // Randomised volumes, including the shapes a moving entity actually produces.
        for (int i = 0; i < 40000; i++) {
            cases.add(new int[]{1 + rng.nextInt(9), 1 + rng.nextInt(9), 1 + rng.nextInt(9)});
        }

        for (int[] c : cases) {
            int ox = rng.nextInt(2001) - 1000, oy = rng.nextInt(385) - 64, oz = rng.nextInt(2001) - 1000;
            int ex = ox + c[0] - 1, ey = oy + c[1] - 1, ez = oz + c[2] - 1;

            List<Step> van = walkVanilla(ox, oy, oz, ex, ey, ez);
            List<Step> ult = walkUltima(ox, oy, oz, ex, ey, ez, false);
            if (!van.equals(ult)) fullMismatch++;

            List<Step> expectedInterior = van.stream().filter(s -> s.type() == 0).toList();
            List<Step> actualInterior = walkUltima(ox, oy, oz, ex, ey, ez, true);
            if (!expectedInterior.equals(actualInterior)) {
                interiorMismatch++;
                if (interiorMismatch <= 3) {
                    System.out.printf("  interior mismatch w=%d h=%d d=%d expected=%d actual=%d%n",
                            c[0], c[1], c[2], expectedInterior.size(), actualInterior.size());
                }
            }

            volumes++;
            positions += van.size();
            interiorPositions += expectedInterior.size();
        }

        System.out.printf("volumes tested          : %d%n", volumes);
        System.out.printf("positions compared      : %d%n", positions);
        System.out.printf("full-traversal mismatch : %d%n", fullMismatch);
        System.out.printf("interior mismatch       : %d%n", interiorMismatch);
        System.out.printf("interior share of visits: %.2f%% (shell = %.2f%%)%n",
                100.0 * interiorPositions / positions, 100.0 - 100.0 * interiorPositions / positions);

        // Shell share for the volume shape a normal entity movement actually produces.
        // BlockCollisions grows the collider box by one block in every direction, so a collider
        // spanning (sx, sy, sz) blocks yields a cursor volume of (sx+2, sy+2, sz+2).
        System.out.println();
        System.out.println("shell share by collider block span (cursor volume = span + 2 per axis):");
        for (int[] span : new int[][]{{1,2,1},{1,3,1},{2,2,2},{2,3,2},{3,3,3},{4,4,4}}) {
            int w = span[0] + 2, h = span[1] + 2, d = span[2] + 2;
            int total = w * h * d;
            int inside = Math.max(0, w - 2) * Math.max(0, h - 2) * Math.max(0, d - 2);
            System.out.printf("  span %dx%dx%d -> volume %dx%dx%d = %3d positions, interior %2d (%.1f%%), shell %3d (%.1f%%)%n",
                    span[0], span[1], span[2], w, h, d, total, inside,
                    100.0 * inside / total, total - inside, 100.0 * (total - inside) / total);
        }
    }
}
