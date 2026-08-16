package dev.ultima.review;

import dev.ultima.meshing.BlockRenderFlags;
import dev.ultima.meshing.MeshEquivalence;
import dev.ultima.meshing.MeshVisit;
import dev.ultima.meshing.MesherMetrics;
import dev.ultima.meshing.OcclusionFaces;
import dev.ultima.meshing.PackedSectionVolume;
import dev.ultima.meshing.PackedVisitScanner;
import dev.ultima.meshing.SectionFixtures;
import dev.ultima.meshing.SectionIndex;
import dev.ultima.meshing.VanillaBlockSeed;
import dev.ultima.meshing.VanillaVisitOracle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

final class DataMesherChecks {
    private DataMesherChecks() {
    }

    static void run() {
        testIndexBoundaries();
        testVisitOrderMatchesBetweenClosed();
        testDefaultSeedMatchesVanilla();
        testRepresentativeVisitPlans();
        testNegativeCoordinatesAndHalo();
        testOcclusionAdjacency();
        testMeshEquivalenceRule();
        testFrozenVolumeRejectsWrites();
        testMetricsRecord();
    }

    private static void testIndexBoundaries() {
        if (SectionIndex.VOLUME != 18 * 18 * 18) {
            throw new AssertionError("18³ volume");
        }
        if (SectionIndex.interior(0, 0, 0) != SectionIndex.packed(1, 1, 1)) {
            throw new AssertionError("interior origin mapping");
        }
        if (SectionIndex.interior(15, 15, 15) != SectionIndex.packed(16, 16, 16)) {
            throw new AssertionError("interior far corner");
        }
        if (SectionIndex.neighbor(0, 0, 0, -1, 0, 0) != SectionIndex.packed(0, 1, 1)) {
            throw new AssertionError("negative-X halo neighbor");
        }
        if (SectionIndex.neighbor(15, 7, 3, 1, 0, 0) != SectionIndex.packed(17, 8, 4)) {
            throw new AssertionError("positive-X halo neighbor at section edge");
        }
        if (!SectionIndex.inExtent(0, 0, 0) || SectionIndex.inExtent(-1, 0, 0) || SectionIndex.inExtent(18, 0, 0)) {
            throw new AssertionError("extent bounds");
        }
        if (SectionIndex.haloWorldX(-32, 0) != -33 || SectionIndex.worldX(-32, 0) != -32) {
            throw new AssertionError("negative origin world mapping");
        }
    }

    private static void testVisitOrderMatchesBetweenClosed() {
        BlockPos min = new BlockPos(-48, -64, 16);
        int index = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, min.offset(15, 15, 15))) {
            int x = index % 16;
            int slice = index / 16;
            int y = slice % 16;
            int z = slice / 16;
            if (pos.getX() != min.getX() + x || pos.getY() != min.getY() + y || pos.getZ() != min.getZ() + z) {
                throw new AssertionError("betweenClosed order at " + index);
            }
            if (SectionIndex.worldX(min.getX(), x) != pos.getX()) {
                throw new AssertionError("section world X");
            }
            index++;
        }
        if (index != 4096) {
            throw new AssertionError("16³ visits");
        }
    }

    private static void testDefaultSeedMatchesVanilla() {
        int[][] samples = {
                {0, 0, 0},
                {1, 64, -1},
                {-30000000, -64, 30000000},
                {15, 319, -32},
                {-33, 70, -17},
        };
        for (int[] sample : samples) {
            long vanilla = Mth.getSeed(sample[0], sample[1], sample[2]);
            long ultima = VanillaBlockSeed.defaultSeed(sample[0], sample[1], sample[2]);
            if (vanilla != ultima) {
                throw new AssertionError("default seed mismatch at " + sample[0] + "," + sample[1] + "," + sample[2]);
            }
        }
    }

    private static void testRepresentativeVisitPlans() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 1, 1, 1, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 2, 1, 1, SectionFixtures.CUTOUT);
        SectionFixtures.setInterior(states, 3, 1, 1, SectionFixtures.LEAVES);
        SectionFixtures.setInterior(states, 4, 1, 1, SectionFixtures.PLANT);
        SectionFixtures.setInterior(states, 5, 1, 1, SectionFixtures.STAIRS);
        SectionFixtures.setInterior(states, 6, 1, 1, SectionFixtures.SLAB);
        SectionFixtures.setInterior(states, 7, 1, 1, SectionFixtures.FENCE);
        SectionFixtures.setInterior(states, 8, 1, 1, SectionFixtures.FLUID);
        SectionFixtures.setInterior(states, 9, 1, 1, SectionFixtures.TRANSPARENT);
        SectionFixtures.setInterior(states, 10, 1, 1, SectionFixtures.TINT);
        SectionFixtures.setInterior(states, 11, 1, 1, SectionFixtures.LIGHT);
        SectionFixtures.setInterior(states, 12, 1, 1, SectionFixtures.BLOCK_ENTITY);
        SectionFixtures.setInterior(states, 13, 1, 1, SectionFixtures.RANDOM);
        SectionFixtures.setHalo(states, 0, 2, 2, SectionFixtures.FULL_CUBE);
        List<MeshVisit> visits = assertPlansMatch(-32, 64, 16, states);
        boolean sawFluid = false;
        boolean sawModel = false;
        boolean sawEntity = false;
        for (MeshVisit visit : visits) {
            sawFluid |= BlockRenderFlags.hasFluid(visit.flags());
            sawModel |= BlockRenderFlags.model(visit.flags());
            sawEntity |= visit.blockEntitySlot() >= 0;
        }
        if (!sawFluid || !sawModel || !sawEntity) {
            throw new AssertionError("representative section must include fluid, model, and block entity visits");
        }
    }

    private static void testNegativeCoordinatesAndHalo() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 0, 0, 0, SectionFixtures.FENCE);
        SectionFixtures.setHalo(states, 0, 1, 1, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 15, 15, 15, SectionFixtures.FLUID);
        SectionFixtures.setHalo(states, 17, 16, 16, SectionFixtures.TRANSPARENT);
        assertPlansMatch(-320, -64, -16, states);
        PackedSectionVolume volume = SectionFixtures.pack(-320, -64, -16, states);
        MeshVisit first = PackedVisitScanner.walk(volume).getFirst();
        if (first.neighborNegX() != SectionFixtures.FULL_CUBE) {
            throw new AssertionError("chunk-boundary -X neighbor must come from the halo");
        }
        MeshVisit last = PackedVisitScanner.walk(volume).getLast();
        if (last.neighborPosX() != SectionFixtures.TRANSPARENT) {
            throw new AssertionError("chunk-boundary +X neighbor must come from the halo");
        }
        if (first.worldX() != -320 || first.worldY() != -64 || first.worldZ() != -16) {
            throw new AssertionError("negative world coordinates on first visit");
        }
    }

    private static void testOcclusionAdjacency() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 4, 4, 4, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 5, 4, 4, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 4, 4, 5, SectionFixtures.TRANSPARENT);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        List<OcclusionFaces.Face> packed = OcclusionFaces.fromVolume(volume, SectionFixtures.FULL_CUBE);
        List<OcclusionFaces.Face> halo = OcclusionFaces.fromHaloArray(states, SectionFixtures.FULL_CUBE);
        if (!OcclusionFaces.packedFaceKeys(packed).equals(OcclusionFaces.packedFaceKeys(halo))) {
            throw new AssertionError("packed occlusion faces must match 3D-array oracle");
        }
        boolean sharedFace = packed.stream().anyMatch(face -> face.x() == 4 && face.y() == 4 && face.z() == 4
                && face.nx() == 1);
        if (sharedFace) {
            throw new AssertionError("full-cube pair must not emit the occluded +X face");
        }
        boolean transparentFace = packed.stream().anyMatch(face -> face.x() == 4 && face.z() == 4 && face.nz() == 1);
        if (transparentFace) {
            throw new AssertionError("transparent neighbor is not air; full-cube occlusion test treats only air as open");
        }
    }

    private static void testMeshEquivalenceRule() {
        MeshEquivalence.TerrainVertex a = new MeshEquivalence.TerrainVertex(0, 0.0F, 1.0F, 2.0F, 0xFFFFFFFF, 0.1F, 0.2F, 15, 15);
        MeshEquivalence.TerrainVertex b = new MeshEquivalence.TerrainVertex(0, 1.0F, 1.0F, 2.0F, 0xFFFFFFFF, 0.3F, 0.2F, 15, 15);
        MeshEquivalence.TerrainVertex c = new MeshEquivalence.TerrainVertex(0, 1.0F, 0.0F, 2.0F, 0xFFFFFFFF, 0.3F, 0.4F, 15, 15);
        MeshEquivalence.TerrainVertex d = new MeshEquivalence.TerrainVertex(0, 0.0F, 0.0F, 2.0F, 0xFFFFFFFF, 0.1F, 0.4F, 15, 15);
        MeshEquivalence.TerrainVertex e = new MeshEquivalence.TerrainVertex(1, 0.0F, 1.0F, 3.0F, 0xFF00FF00, 0.0F, 0.0F, 0, 0);
        MeshEquivalence.TerrainVertex f = new MeshEquivalence.TerrainVertex(1, 1.0F, 1.0F, 3.0F, 0xFF00FF00, 1.0F, 0.0F, 0, 0);
        MeshEquivalence.TerrainVertex g = new MeshEquivalence.TerrainVertex(1, 1.0F, 0.0F, 3.0F, 0xFF00FF00, 1.0F, 1.0F, 0, 0);
        MeshEquivalence.TerrainVertex h = new MeshEquivalence.TerrainVertex(1, 0.0F, 0.0F, 3.0F, 0xFF00FF00, 0.0F, 1.0F, 0, 0);
        List<MeshEquivalence.TerrainVertex> first = List.of(a, b, c, d, e, f, g, h);
        List<MeshEquivalence.TerrainVertex> swapped = List.of(e, f, g, h, a, b, c, d);
        if (MeshEquivalence.compare(first, first) != MeshEquivalence.Result.IDENTICAL_ORDERED) {
            throw new AssertionError("identical ordered vertices");
        }
        if (MeshEquivalence.compare(first, swapped) != MeshEquivalence.Result.EQUIVALENT_QUAD_MULTISET) {
            throw new AssertionError("quad multiset must accept layer reordering");
        }
        List<MeshEquivalence.TerrainVertex> mutated = new ArrayList<>(first);
        mutated.set(0, new MeshEquivalence.TerrainVertex(0, 0.0F, 1.0F, 2.0F, 0xFF000000, 0.1F, 0.2F, 15, 15));
        if (MeshEquivalence.compare(first, mutated) != MeshEquivalence.Result.DIFFERENT) {
            throw new AssertionError("color change is not equivalent");
        }
    }

    private static void testFrozenVolumeRejectsWrites() {
        PackedSectionVolume volume = new PackedSectionVolume();
        volume.begin(0, 0, 0);
        volume.freeze();
        try {
            volume.setCell(0, 1, (byte)0);
            throw new AssertionError("frozen volume must reject writes");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    private static void testMetricsRecord() {
        MesherMetrics.reset();
        MesherMetrics.recordCompile(10L, 20L, 3L, 2L, 1L, 8L, 224L, 5L, 7L);
        MesherMetrics.Snapshot snapshot = MesherMetrics.snapshot();
        if (snapshot.snapshotBuildNs() != 10L
                || snapshot.meshBuildNs() != 20L
                || snapshot.blocksVisited() != 3L
                || snapshot.modelCalls() != 2L
                || snapshot.fluidCalls() != 1L
                || snapshot.verticesEmitted() != 8L
                || snapshot.bytesEmitted() != 224L
                || snapshot.temporaryAllocationProxy() != 5L
                || snapshot.rebuildCount() != 1L
                || snapshot.workerQueueLatencyNs() != 7L) {
            throw new AssertionError("mesher metrics snapshot");
        }
    }

    private static List<MeshVisit> assertPlansMatch(
            final int originX,
            final int originY,
            final int originZ,
            final int[][][] states) {
        byte[][][] flags = SectionFixtures.flagsOf(states);
        int[][][] entities = SectionFixtures.entitySlots(states);
        List<MeshVisit> oracle = VanillaVisitOracle.walk(originX, originY, originZ, states, flags, entities);
        PackedSectionVolume volume = SectionFixtures.pack(originX, originY, originZ, states);
        List<MeshVisit> packed = PackedVisitScanner.walk(volume);
        if (!oracle.equals(packed)) {
            throw new AssertionError("visit plan mismatch origin=" + originX + "," + originY + "," + originZ
                    + " oracle=" + oracle + " packed=" + packed);
        }
        if (oracle.isEmpty()) {
            throw new AssertionError("representative case produced no visits");
        }
        return oracle;
    }
}
