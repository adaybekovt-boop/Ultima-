package dev.ultima.meshing;

import java.util.Locale;

/**
 * CPU-only comparison of production snapshot flags versus the old 6-face
 * occlusion/skip walk. This is <strong>not</strong> an FPS or GPU claim.
 *
 * <p>Production {@code RenderSectionSnapshot.flagsOf} packs air / solid / BE /
 * fluid / model only. The heavy path here is the work that used to run for
 * every one of the 5832 halo cells: {@code getFaceOcclusionShape} +
 * {@code skipRendering} on all six directions.
 */
public final class SnapshotFlagHotPath {
    private static final int CELLS = SectionIndex.VOLUME;

    public record Sample(long leanNs, long heavyNs, int cells) {
        public double ratio() {
            return this.leanNs <= 0L ? Double.NaN : (double)this.heavyNs / (double)this.leanNs;
        }
    }

    private SnapshotFlagHotPath() {
    }

    public static Sample run(final int iterations) {
        int[] lean = new int[CELLS];
        int[] heavy = new int[CELLS];
        packLean(lean);
        packHeavy(heavy);

        long leanNs = Long.MAX_VALUE;
        long heavyNs = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            packLean(lean);
            leanNs = Math.min(leanNs, System.nanoTime() - start);
            start = System.nanoTime();
            packHeavy(heavy);
            heavyNs = Math.min(heavyNs, System.nanoTime() - start);
        }
        return new Sample(leanNs, heavyNs, CELLS);
    }

    public static String format(final Sample sample) {
        return String.format(
                Locale.ROOT,
                "snapshot_flags_hot_path: lean=%.3fms heavy=%.3fms heavy/lean=%.2fx cells=%d | CPU flag-pack time only, NOT a real FPS/GPU claim, requires hardware A/B before any performance statement",
                sample.leanNs() / 1_000_000.0,
                sample.heavyNs() / 1_000_000.0,
                sample.ratio(),
                sample.cells());
    }

    /**
     * Same bits production {@code flagsOf} writes: no occlusion-shape, no skip.
     */
    static void packLean(final int[] out) {
        for (int i = 0; i < out.length; i++) {
            boolean air = (i & 7) == 0;
            out[i] = BlockRenderFlags.pack(
                    air,
                    !air,
                    false,
                    false,
                    !air,
                    false,
                    false,
                    false);
        }
    }

    /**
     * Old hot-path cost: six face occlusion/skip queries per cell, then pack.
     * The boolean work stands in for {@code getFaceOcclusionShape}/{@code skipRendering}.
     */
    static void packHeavy(final int[] out) {
        for (int i = 0; i < out.length; i++) {
            boolean air = (i & 7) == 0;
            boolean full = !air;
            boolean empty = air;
            boolean skip = !air;
            for (int face = 0; face < 6; face++) {
                boolean faceFull = !air && (face != 3 || (i & 1) == 0);
                boolean faceEmpty = air || !faceFull;
                full &= faceFull;
                empty &= faceEmpty;
                skip &= !air && (i & 3) == 0;
            }
            out[i] = BlockRenderFlags.pack(
                    air,
                    !air,
                    false,
                    false,
                    !air,
                    full,
                    empty,
                    skip);
        }
    }
}
