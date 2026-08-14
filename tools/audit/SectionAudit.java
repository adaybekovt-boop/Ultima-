import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Second-pass audit harness for Ultima's `entity_section_lookup` module.
 *
 * Part 1 re-verifies the ordering claim (visited set and order identical to the vanilla tree scan).
 * Part 2 measures the *work* each side performs, which is what the module's fallback guard is
 * supposed to bound. The guard on the branch is
 *
 *     if (candidates > 1024 && candidates > sectionIds.size()) -> fall back
 *
 * so a query whose candidate volume is under 1024 sections always takes the direct path, no matter
 * how few entity sections actually exist. Part 2 quantifies that window.
 */
public final class SectionAudit {

    /* ---- net.minecraft.core.SectionPos packing ---- */
    static long asLong(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) y & 0xFFFFFL) | ((long) z & 0x3FFFFFL) << 20;
    }
    static int unpackX(long p) { return (int) (p >> 42); }
    static int unpackY(long p) { return (int) (p << 44 >> 44); }
    static int unpackZ(long p) { return (int) (p << 22 >> 42); }
    static int posToSectionCoord(double v) { return (int) Math.floor(v) >> 4; }

    /** Counts of the work each implementation performs, so cost is compared, not just correctness. */
    static final class Work {
        long treeSubSets, treeEntriesWalked, hashProbes, hashHits;
    }

    /* ---- Faithful port of vanilla forEachAccessibleNonEmptySection ---- */
    static List<Long> vanilla(LongSortedSet sectionIds, Long2ObjectMap<String> sections,
                              double[] bb, Work work) {
        int xMin = posToSectionCoord(bb[0] - 2.0);
        int yMin = posToSectionCoord(bb[1] - 4.0);
        int zMin = posToSectionCoord(bb[2] - 2.0);
        int xMax = posToSectionCoord(bb[3] + 2.0);
        int yMax = posToSectionCoord(bb[4] + 0.0);
        int zMax = posToSectionCoord(bb[5] + 2.0);

        List<Long> visited = new ArrayList<>();
        for (int x = xMin; x <= xMax; x++) {
            long from = asLong(x, 0, 0);
            long to = asLong(x, -1, -1);
            work.treeSubSets++;
            LongIterator it = sectionIds.subSet(from, to + 1L).iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                work.treeEntriesWalked++;
                int y = unpackY(key);
                int z = unpackZ(key);
                if (y >= yMin && y <= yMax && z >= zMin && z <= zMax) {
                    if (sections.get(key) != null) visited.add(key);
                }
            }
        }
        return visited;
    }

    /* ---- Ultima's EntitySectionStorageMixin, transcribed from the branch ---- */
    static final long BUDGET = 1024L;

    static List<Long> ultima(LongSortedSet sectionIds, Long2ObjectMap<String> sections,
                             double[] bb, Work work, boolean tightenedGuard) {
        int xMin = posToSectionCoord(bb[0] - 2.0);
        int yMin = posToSectionCoord(bb[1] - 4.0);
        int zMin = posToSectionCoord(bb[2] - 2.0);
        int xMax = posToSectionCoord(bb[3] + 2.0);
        int yMax = posToSectionCoord(bb[4] + 0.0);
        int zMax = posToSectionCoord(bb[5] + 2.0);
        if (xMax < xMin || yMax < yMin || zMax < zMin) return List.of();

        long candidates = ((long) xMax - xMin + 1L) * ((long) yMax - yMin + 1L) * ((long) zMax - zMin + 1L);
        boolean fallBack = tightenedGuard
                ? candidates > sectionIds.size()
                : candidates > BUDGET && candidates > sectionIds.size();
        if (fallBack) return vanilla(sectionIds, sections, bb, work);

        List<Long> visited = new ArrayList<>();
        for (int x = xMin; x <= xMax; x++) {
            for (int zHalf = 0; zHalf < 2; zHalf++) {
                int zFrom = zHalf == 0 ? Math.max(zMin, 0) : zMin;
                int zTo = zHalf == 0 ? zMax : Math.min(zMax, -1);
                for (int z = zFrom; z <= zTo; z++) {
                    for (int yHalf = 0; yHalf < 2; yHalf++) {
                        int yFrom = yHalf == 0 ? Math.max(yMin, 0) : yMin;
                        int yTo = yHalf == 0 ? yMax : Math.min(yMax, -1);
                        for (int y = yFrom; y <= yTo; y++) {
                            work.hashProbes++;
                            String s = sections.get(asLong(x, y, z));
                            if (s != null) { work.hashHits++; visited.add(asLong(x, y, z)); }
                        }
                    }
                }
            }
        }
        return visited;
    }

    record World(LongSortedSet ids, Long2ObjectMap<String> map) {}

    /** Populates sections in a cube of chunk columns, `yLayers` sections tall. */
    static World build(Random rng, int columns, int yLayers, int spreadChunks) {
        LongSortedSet ids = new LongAVLTreeSet();
        Long2ObjectMap<String> map = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < columns; i++) {
            int cx = rng.nextInt(spreadChunks * 2 + 1) - spreadChunks;
            int cz = rng.nextInt(spreadChunks * 2 + 1) - spreadChunks;
            for (int k = 0; k < yLayers; k++) {
                int cy = rng.nextInt(24) - 4;
                long key = asLong(cx, cy, cz);
                if (map.putIfAbsent(key, "s") == null) ids.add(key);
            }
        }
        return new World(ids, map);
    }

    public static void main(String[] args) {
        Random rng = new Random(20260814L);

        /* ---------------- Part 1: order + visited-set equivalence ---------------- */
        long queries = 0, setMismatch = 0, orderMismatch = 0;
        for (int trial = 0; trial < 400; trial++) {
            World w = build(rng, 20 + rng.nextInt(400), 1 + rng.nextInt(6), 4 + rng.nextInt(40));
            for (int q = 0; q < 60; q++) {
                // Deliberately straddle zero on every axis, which is where the key masking
                // reorders negative coordinates above non-negative ones.
                double cx = rng.nextInt(600) - 300, cy = rng.nextInt(200) - 100, cz = rng.nextInt(600) - 300;
                double sx = rng.nextDouble() * 40, sy = rng.nextDouble() * 40, sz = rng.nextDouble() * 40;
                double[] bb = {cx, cy, cz, cx + sx, cy + sy, cz + sz};

                List<Long> van = vanilla(w.ids(), w.map(), bb, new Work());
                List<Long> ult = ultima(w.ids(), w.map(), bb, new Work(), false);
                if (!List.copyOf(van).equals(ult)) {
                    if (!new java.util.HashSet<>(van).equals(new java.util.HashSet<>(ult))) setMismatch++;
                    else orderMismatch++;
                }
                queries++;
            }
        }
        System.out.println("== Part 1: semantic equivalence ==");
        System.out.printf("queries compared     : %d%n", queries);
        System.out.printf("visited-set mismatch : %d%n", setMismatch);
        System.out.printf("visit-order mismatch : %d%n", orderMismatch);

        /* ---------------- Part 2: worst-case work, current vs tightened guard ---------------- */
        System.out.println();
        System.out.println("== Part 2: work performed per query (probes vs tree entries walked) ==");
        System.out.printf("%-34s %10s %12s %12s %12s%n",
                "scenario", "sections", "vanilla", "ultima(now)", "ultima(fix)");

        record Scenario(String name, int columns, int yLayers, int spread, double boxSpan) {}
        List<Scenario> scenarios = List.of(
                // A sparse world: a handful of populated sections, but an AI/target query with a
                // wide box. This is the window the `> 1024` clause leaves open.
                new Scenario("sparse world, 64-block AI query", 3, 1, 400, 64),
                new Scenario("sparse world, 128-block query", 3, 1, 400, 128),
                new Scenario("small server, 64-block AI query", 40, 2, 200, 64),
                new Scenario("small server, 32-block query", 40, 2, 200, 32),
                // The dense cases the module was designed for.
                new Scenario("dense world, entity-sized query", 800, 6, 24, 1),
                new Scenario("dense world, 32-block query", 800, 6, 24, 32),
                new Scenario("very dense, entity-sized query", 2500, 8, 24, 1));

        for (Scenario sc : scenarios) {
            Random r2 = new Random(99L);
            World w = build(r2, sc.columns(), sc.yLayers(), sc.spread());
            Work wv = new Work(), wu = new Work(), wf = new Work();
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double cx = r2.nextInt(400) - 200, cy = r2.nextInt(120) - 60, cz = r2.nextInt(400) - 200;
                double[] bb = {cx, cy, cz, cx + sc.boxSpan(), cy + sc.boxSpan(), cz + sc.boxSpan()};
                vanilla(w.ids(), w.map(), bb, wv);
                ultima(w.ids(), w.map(), bb, wu, false);
                ultima(w.ids(), w.map(), bb, wf, true);
            }
            double vanWork = (wv.treeEntriesWalked + wv.treeSubSets * 4.0) / n; // subSet ~ O(log n) descent
            double ultWork = (wu.hashProbes + wu.treeEntriesWalked + wu.treeSubSets * 4.0) / n;
            double fixWork = (wf.hashProbes + wf.treeEntriesWalked + wf.treeSubSets * 4.0) / n;
            System.out.printf("%-34s %10d %12.1f %12.1f %12.1f%n",
                    sc.name(), w.ids().size(), vanWork, ultWork, fixWork);
            // Raw counts too, so the comparison does not depend on how a subSet descent is weighted.
            System.out.printf("%-34s %10s   raw: vanilla %.1f subSets + %.1f entries | now %.1f probes | fix %.1f probes%n",
                    "", "", wv.treeSubSets / (double) n, wv.treeEntriesWalked / (double) n,
                    wu.hashProbes / (double) n, wf.hashProbes / (double) n);
        }

        /* ---------------- Part 2b: wall-clock, assumption-free ---------------- */
        System.out.println();
        System.out.println("== Part 2b: wall-clock ns per query (sparse world, 128-block query) ==");
        {
            Random r4 = new Random(99L);
            World w = build(r4, 3, 1, 400);
            int n = 200000;
            double[][] boxes = new double[n][];
            for (int i = 0; i < n; i++) {
                double cx = r4.nextInt(400) - 200, cy = r4.nextInt(120) - 60, cz = r4.nextInt(400) - 200;
                boxes[i] = new double[]{cx, cy, cz, cx + 128, cy + 128, cz + 128};
            }
            long blackhole = 0;
            for (int warm = 0; warm < 3; warm++) {
                for (double[] bb : boxes) {
                    blackhole += vanilla(w.ids(), w.map(), bb, new Work()).size();
                    blackhole += ultima(w.ids(), w.map(), bb, new Work(), false).size();
                    blackhole += ultima(w.ids(), w.map(), bb, new Work(), true).size();
                }
            }
            long t0 = System.nanoTime();
            for (double[] bb : boxes) blackhole += vanilla(w.ids(), w.map(), bb, new Work()).size();
            long t1 = System.nanoTime();
            for (double[] bb : boxes) blackhole += ultima(w.ids(), w.map(), bb, new Work(), false).size();
            long t2 = System.nanoTime();
            for (double[] bb : boxes) blackhole += ultima(w.ids(), w.map(), bb, new Work(), true).size();
            long t3 = System.nanoTime();
            System.out.printf("vanilla       : %6.0f ns/query%n", (t1 - t0) / (double) n);
            System.out.printf("ultima (now)  : %6.0f ns/query  <-- current guard takes the direct path%n", (t2 - t1) / (double) n);
            System.out.printf("ultima (fix)  : %6.0f ns/query%n", (t3 - t2) / (double) n);
            if (blackhole == Long.MIN_VALUE) System.out.println("unreachable");
        }

        /* ---------------- Part 3: does the tightened guard ever change results? ---------------- */
        System.out.println();
        System.out.println("== Part 3: tightened guard preserves results ==");
        long q3 = 0, mism3 = 0;
        Random r3 = new Random(4242L);
        for (int trial = 0; trial < 300; trial++) {
            World w = build(r3, 5 + r3.nextInt(500), 1 + r3.nextInt(8), 4 + r3.nextInt(60));
            for (int q = 0; q < 60; q++) {
                double cx = r3.nextInt(600) - 300, cy = r3.nextInt(200) - 100, cz = r3.nextInt(600) - 300;
                double sp = r3.nextDouble() * 120;
                double[] bb = {cx, cy, cz, cx + sp, cy + sp, cz + sp};
                List<Long> van = vanilla(w.ids(), w.map(), bb, new Work());
                List<Long> fix = ultima(w.ids(), w.map(), bb, new Work(), true);
                if (!van.equals(fix)) mism3++;
                q3++;
            }
        }
        System.out.printf("queries compared     : %d%n", q3);
        System.out.printf("mismatches           : %d%n", mism3);
    }
}
