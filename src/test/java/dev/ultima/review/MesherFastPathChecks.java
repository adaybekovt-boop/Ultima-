package dev.ultima.review;

import dev.ultima.client.benchmark.ClientFrameBenchmark;
import dev.ultima.client.renderer.meshing.CubeModelCache;
import dev.ultima.client.renderer.meshing.HybridSectionMesher;
import dev.ultima.client.renderer.meshing.HybridSectionMesherProductionChecks;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import dev.ultima.meshing.BlockRenderFlags;
import dev.ultima.meshing.CpuMeshingBenchmark;
import dev.ultima.meshing.FastPathCriteria;
import dev.ultima.meshing.FullCubeTemplates;
import dev.ultima.meshing.MesherBenchmarkScenes;
import dev.ultima.meshing.MesherCircuitBreaker;
import dev.ultima.meshing.MesherSectionFailOpen;
import dev.ultima.meshing.MesherWorldFixtures;
import dev.ultima.meshing.FastPathCubeMesher;
import dev.ultima.meshing.MeshEquivalence;
import dev.ultima.meshing.SnapshotFlagHotPath;
import dev.ultima.meshing.MeshVisit;
import dev.ultima.meshing.MesherMetrics;
import dev.ultima.meshing.OcclusionMask;
import dev.ultima.meshing.PackedLightVolume;
import dev.ultima.meshing.PackedSectionVolume;
import dev.ultima.meshing.PackedVisitScanner;
import dev.ultima.meshing.SectionFixtures;
import dev.ultima.meshing.SectionIndex;
import dev.ultima.meshing.VanillaBlockSeed;
import dev.ultima.meshing.VanillaCubeOracle;
import dev.ultima.meshing.VanillaVisitOracle;
import dev.ultima.meshing.VanillaWeightedPick;
import dev.ultima.meshing.VanillaCubeCoverage;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.CardinalLighting;
import org.joml.Vector3f;

final class MesherFastPathChecks {
    private static final List<String> CASE_RESULTS = new ArrayList<>();

    private MesherFastPathChecks() {
    }

    static void run() {
        CASE_RESULTS.clear();
        testGateDefaultOffAndIsolated();
        testIndexBoundaries();
        testVisitOrderMatchesBetweenClosed();
        testDefaultSeedMatchesVanilla();
        testRepresentativeVisitPlans();
        testNegativeCoordinatesAndHalo();
        testOcclusionAdjacency();
        testOcclusionMaskMatchesVanillaShouldRenderFace();
        testFastPathCriteria();
        testTemplatesMatchVanillaFaceInfo();
        testProductionPathUsesVanillaCullingAndLighting();
        testIsolatedFullCubeGeometry();
        testAdjacentFullCubesCullSharedFace();
        testSectionEdgeHaloOcclusion();
        testNegativeWorldOriginGeometry();
        testFarCornerGeometry();
        testTwoByTwoCluster();
        testCheckerboard();
        testTintedAndGlowstoneAndGlass();
        testFallbackNeverEmitsFastGeometry();
        testFrozenVolumeRejectsWrites();
        testMetricsRecord();
        testCpuMeshingTime();
        testCubeCacheInvalidatesOnModelSetReload();
        testSectionFailOpenDoesNotCrashOtherSections();
        testCircuitBreakerIsPerBlockStateNotSection();
        testGlassAndTranslucentAlwaysFallback();
        testAnimatedCubeUvsAreFrameIndependent();
        testMultipleTintProviders();
        testUnloadedNeighborSectionEdge();
        testCompileAfterReloadIsFreshNotStale();
        testRealisticCpuDatasets();
        testMesherBenchmarkScenes();
        testWeightedAndPerStateCubeEquivalence();
        testGrassOverlayAndMultipartStayFallback();
        testVanillaWeightedPickMatchesVanillaRng();
        testVanillaCubeCoverageCatalog();
        System.out.println("Mesher fast-path equivalence cases:");
        for (String line : CASE_RESULTS) {
            System.out.println("  " + line);
        }
        System.out.println("Mesher fast-path checks passed.");
    }

    private static void testGateDefaultOffAndIsolated() {
        UltimaModules.Module module = UltimaModules.byKey("mesher_fast_path");
        if (module == null || module.enabledByDefault() || !module.clientOnly()) {
            throw new AssertionError("mesher_fast_path must be a client opt-in module");
        }
        if (!module.dependencies().isEmpty()) {
            throw new AssertionError("mesher_fast_path must not depend on retained_terrain");
        }
        for (String renderer : List.of("sodium", "iris", "canvas")) {
            if (!module.incompatibleMods().contains(renderer)) {
                throw new AssertionError("mesher_fast_path must auto-disable with " + renderer);
            }
        }
        try {
            Constructor<UltimaConfig> constructor = UltimaConfig.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            Map<String, Boolean> requested = new LinkedHashMap<>();
            for (UltimaModules.Module item : UltimaModules.all()) {
                requested.put(item.key(), false);
            }
            requested.put("mesher_fast_path", true);
            requested.put("retained_terrain", false);
            requested.put("java_mesher", true);
            UltimaConfig config = constructor.newInstance(requested);
            boolean enabled = config.isEnabled("mesher_fast_path");
            String reason = config.resolve("mesher_fast_path").reason();
            if (enabled) {
                if (!"enabled".equals(reason)) {
                    throw new AssertionError("enabled mesher_fast_path reason: " + reason);
                }
            } else if (!"not_client_environment".equals(reason)) {
                throw new AssertionError("mesher_fast_path should enable without retained_terrain, got " + reason);
            }
            if (config.isEnabled("retained_terrain")) {
                throw new AssertionError("retained_terrain must stay off");
            }
            pass("gate_default_off_isolated");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not construct UltimaConfig", e);
        }
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
        pass("index_boundaries");
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
            index++;
        }
        if (index != 4096) {
            throw new AssertionError("16³ visits");
        }
        pass("visit_order_betweenClosed");
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
        pass("vanilla_block_seed");
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
        pass("representative_visit_plan");
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
        pass("negative_coords_and_halo");
    }

    private static void testOcclusionAdjacency() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 4, 4, 4, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 5, 4, 4, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 4, 4, 5, SectionFixtures.TRANSPARENT);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        int mask = OcclusionMask.visibleFaces(volume, 4, 4, 4);
        if (OcclusionMask.visible(mask, Direction.EAST)) {
            throw new AssertionError("full-cube pair must not emit the occluded +X face");
        }
        if (!OcclusionMask.visible(mask, Direction.SOUTH)) {
            throw new AssertionError("empty-occluder (glass) neighbor must keep the +Z face");
        }
        pass("occlusion_adjacency");
    }

    private static void testOcclusionMaskMatchesVanillaShouldRenderFace() {
        int self = SectionFixtures.flags(SectionFixtures.FULL_CUBE);
        if (OcclusionMask.simpleShouldRenderFace(self, SectionFixtures.flags(SectionFixtures.FULL_CUBE)) != 0) {
            throw new AssertionError("full-block occluder must cull, matching Block.shouldRenderFace first branch");
        }
        if (OcclusionMask.simpleShouldRenderFace(self, SectionFixtures.flags(SectionFixtures.AIR)) != 1) {
            throw new AssertionError("air/empty occluder must render");
        }
        if (OcclusionMask.simpleShouldRenderFace(self, SectionFixtures.flags(SectionFixtures.TRANSPARENT)) != 1) {
            throw new AssertionError("glass empty occluder must render");
        }
        if (OcclusionMask.simpleShouldRenderFace(self, SectionFixtures.flags(SectionFixtures.SLAB)) != -1) {
            throw new AssertionError("slab neighbor is not a simple pair and must force fallback");
        }
        pass("occlusion_matches_shouldRenderFace_branches");
    }

    private static void testFastPathCriteria() {
        expectCriteria(SectionFixtures.FULL_CUBE, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.TINT, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.LIGHT, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.WEIGHTED_CUBE, true, FastPathCriteria.Reason.FAST_PATH_WEIGHTED_UNIT_CUBE);
        expectCriteria(SectionFixtures.AXIS_CUBE, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.FACING_CUBE, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.TRANSPARENT, false, FastPathCriteria.Reason.TRANSLUCENT_LAYER);
        expectCriteria(SectionFixtures.AIR, false, FastPathCriteria.Reason.AIR);
        expectCriteria(SectionFixtures.FLUID, false, FastPathCriteria.Reason.HAS_FLUID);
        expectCriteria(SectionFixtures.LEAVES, false, FastPathCriteria.Reason.SKIP_RENDERING);
        expectCriteria(SectionFixtures.RANDOM, false, FastPathCriteria.Reason.WEIGHTED_NON_CUBE);
        expectCriteria(SectionFixtures.GRASS_OVERLAY, false, FastPathCriteria.Reason.WEIGHTED_NON_CUBE);
        expectCriteria(SectionFixtures.STAIRS, false, FastPathCriteria.Reason.NOT_UNIT_CUBE_FACE);
        expectCriteria(SectionFixtures.SLAB, false, FastPathCriteria.Reason.NOT_UNIT_CUBE_FACE);
        expectCriteria(SectionFixtures.FENCE, false, FastPathCriteria.Reason.MULTIPART_MODEL);
        expectCriteria(SectionFixtures.PLANT, false, FastPathCriteria.Reason.NOT_UNIT_CUBE_FACE);
        expectCriteria(SectionFixtures.ANIMATED, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.TINT_FOLIAGE, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        expectCriteria(SectionFixtures.TINT_WATER, true, FastPathCriteria.Reason.FAST_PATH_UNIT_CUBE);
        pass("fast_path_criteria");
    }

    private static void testTemplatesMatchVanillaFaceInfo() {
        Vector3f from = new Vector3f(0.0F, 0.0F, 0.0F);
        Vector3f to = new Vector3f(1.0F, 1.0F, 1.0F);
        for (Direction direction : Direction.values()) {
            FaceInfo info = FaceInfo.fromFacing(direction);
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3f expected = info.getVertexInfo(vertex).select(from, to);
                if (expected.x() != FullCubeTemplates.x(direction, vertex)
                        || expected.y() != FullCubeTemplates.y(direction, vertex)
                        || expected.z() != FullCubeTemplates.z(direction, vertex)) {
                    throw new AssertionError("FaceInfo winding mismatch " + direction + " vertex " + vertex
                            + " expected=" + expected.x() + "," + expected.y() + "," + expected.z()
                            + " template=" + FullCubeTemplates.x(direction, vertex) + ","
                            + FullCubeTemplates.y(direction, vertex) + ","
                            + FullCubeTemplates.z(direction, vertex));
                }
            }
        }
        pass("templates_match_vanilla_FaceInfo");
    }

    private static void testProductionPathUsesVanillaCullingAndLighting() {
        HybridSectionMesherProductionChecks.assertCullingMatchesVanillaShouldRenderFace();
        pass("production_tessellateFastCube_culling_matches_vanilla_shouldRenderFace");
    }

    private static void testIsolatedFullCubeGeometry() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 8, 8, 8, SectionFixtures.FULL_CUBE);
        assertGeometry("isolated_full_cube", 0, 64, 0, states, 6 * 4);
    }

    private static void testAdjacentFullCubesCullSharedFace() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 3, 3, 3, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(states, 4, 3, 3, SectionFixtures.FULL_CUBE);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        assertGeometry("adjacent_full_cubes", volume, 10 * 4);
    }

    private static void testSectionEdgeHaloOcclusion() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 0, 7, 7, SectionFixtures.FULL_CUBE);
        SectionFixtures.setHalo(states, 0, 8, 8, SectionFixtures.FULL_CUBE);
        PackedSectionVolume volume = SectionFixtures.pack(16, 0, 16, states);
        int mask = OcclusionMask.visibleFaces(volume, 0, 7, 7);
        if (OcclusionMask.visible(mask, Direction.WEST)) {
            throw new AssertionError("halo full-cube at -X must cull the west face");
        }
        assertGeometry("section_edge_negx_halo", volume, 5 * 4);
        int[][][] pos = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(pos, 15, 0, 0, SectionFixtures.FULL_CUBE);
        SectionFixtures.setHalo(pos, 17, 1, 1, SectionFixtures.FULL_CUBE);
        assertGeometry("section_edge_posx_halo", 0, 0, 0, pos, 5 * 4);
        int[][][] up = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(up, 8, 15, 8, SectionFixtures.FULL_CUBE);
        SectionFixtures.setHalo(up, 9, 17, 9, SectionFixtures.FULL_CUBE);
        assertGeometry("section_edge_posy_halo", 0, 0, 0, up, 5 * 4);
        int[][][] south = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(south, 8, 8, 15, SectionFixtures.FULL_CUBE);
        SectionFixtures.setHalo(south, 9, 9, 17, SectionFixtures.FULL_CUBE);
        assertGeometry("section_edge_posz_halo", 0, 0, 0, south, 5 * 4);
    }

    private static void testNegativeWorldOriginGeometry() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 0, 0, 0, SectionFixtures.FULL_CUBE);
        assertGeometry("negative_world_origin", -32, -64, -16, states, 6 * 4);
    }

    private static void testFarCornerGeometry() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 15, 15, 15, SectionFixtures.FULL_CUBE);
        assertGeometry("far_corner", 48, 320, -48, states, 6 * 4);
    }

    private static void testTwoByTwoCluster() {
        int[][][] states = SectionFixtures.emptyHalo();
        for (int x = 4; x <= 5; x++) {
            for (int y = 4; y <= 5; y++) {
                for (int z = 4; z <= 5; z++) {
                    SectionFixtures.setInterior(states, x, y, z, SectionFixtures.FULL_CUBE);
                }
            }
        }
        assertGeometry("cube_2x2x2_cluster", 0, 0, 0, states, 24 * 4);
    }

    private static void testCheckerboard() {
        int[][][] states = SectionFixtures.emptyHalo();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (((x + y + z) & 1) == 0) {
                        SectionFixtures.setInterior(states, x, y, z, SectionFixtures.FULL_CUBE);
                    }
                }
            }
        }
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        List<MeshEquivalence.TerrainVertex> oracle = meshOracle(volume);
        assertIdentical("checkerboard", oracle, fast.vertices());
        if (fast.fastPathBlocks() != 2048) {
            throw new AssertionError("checkerboard should fast-path 2048 cubes, got " + fast.fastPathBlocks());
        }
    }

    private static void testTintedAndGlowstoneAndGlass() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 2, 2, 2, SectionFixtures.TINT);
        SectionFixtures.setInterior(states, 4, 2, 2, SectionFixtures.LIGHT);
        SectionFixtures.setInterior(states, 6, 2, 2, SectionFixtures.TRANSPARENT);
        SectionFixtures.setInterior(states, 8, 2, 2, SectionFixtures.FULL_CUBE);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        List<MeshEquivalence.TerrainVertex> oracle = meshOracle(volume);
        assertIdentical("tinted_glowstone_glass_stone", oracle, fast.vertices());
        boolean sawTint = fast.vertices().stream().anyMatch(v -> v.color() != 0 && (v.color() & 0x00FF00) != 0);
        if (!sawTint) {
            throw new AssertionError("tinted cube must multiply grass tint into AO color");
        }
        boolean sawGlassLayer = fast.vertices().stream().anyMatch(v -> v.layer() == 1);
        if (sawGlassLayer) {
            throw new AssertionError("glass/translucent must not emit fast-path vertices");
        }
        if (fast.fallbackBlocks() < 1) {
            throw new AssertionError("glass cell must be counted as fallback");
        }
    }

    private static void testFallbackNeverEmitsFastGeometry() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 1, 1, 1, SectionFixtures.STAIRS);
        SectionFixtures.setInterior(states, 2, 1, 1, SectionFixtures.SLAB);
        SectionFixtures.setInterior(states, 3, 1, 1, SectionFixtures.FENCE);
        SectionFixtures.setInterior(states, 4, 1, 1, SectionFixtures.FLUID);
        SectionFixtures.setInterior(states, 5, 1, 1, SectionFixtures.LEAVES);
        SectionFixtures.setInterior(states, 6, 1, 1, SectionFixtures.PLANT);
        SectionFixtures.setInterior(states, 7, 1, 1, SectionFixtures.RANDOM);
        SectionFixtures.setInterior(states, 8, 1, 1, SectionFixtures.TRANSPARENT);
        SectionFixtures.setInterior(states, 9, 1, 1, SectionFixtures.GRASS_OVERLAY);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        if (!fast.vertices().isEmpty()) {
            throw new AssertionError("fallback-only section must not emit fast-path vertices");
        }
        if (fast.fallbackBlocks() != 9) {
            throw new AssertionError("expected 9 fallback cells, got " + fast.fallbackBlocks());
        }
        int[][][] mixed = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(mixed, 8, 8, 8, SectionFixtures.FULL_CUBE);
        SectionFixtures.setInterior(mixed, 9, 8, 8, SectionFixtures.SLAB);
        PackedSectionVolume mixedVolume = SectionFixtures.pack(0, 0, 0, mixed);
        FastPathCubeMesher.MeshResult mixedMesh = meshFast(mixedVolume);
        if (mixedMesh.fastPathBlocks() != 0) {
            throw new AssertionError("stone next to a slab must fallback; occlusion is not a simple pair");
        }
        pass("fallback_never_emits");
    }

    private static void testFrozenVolumeRejectsWrites() {
        PackedSectionVolume volume = new PackedSectionVolume();
        volume.begin(0, 0, 0);
        volume.freeze();
        try {
            volume.setCell(0, 1, 0);
            throw new AssertionError("frozen volume must reject writes");
        } catch (IllegalStateException expected) {
            pass("frozen_volume");
        }
    }

    private static void testMetricsRecord() {
        MesherMetrics.reset();
        MesherMetrics.recordCompile(10L, 20L, 3L, 2L, 1L, 1L, 1L, 8L, 224L, 5L, 7L);
        MesherMetrics.Snapshot snapshot = MesherMetrics.snapshot();
        if (snapshot.snapshotBuildNs() != 10L
                || snapshot.meshBuildNs() != 20L
                || snapshot.blocksVisited() != 3L
                || snapshot.fastPathBlocks() != 2L
                || snapshot.fallbackBlocks() != 1L
                || snapshot.modelCalls() != 1L
                || snapshot.fluidCalls() != 1L
                || snapshot.verticesEmitted() != 8L
                || snapshot.bytesEmitted() != 224L
                || snapshot.temporaryAllocationProxy() != 5L
                || snapshot.rebuildCount() != 1L
                || snapshot.workerQueueLatencyNs() != 7L) {
            throw new AssertionError("mesher metrics snapshot");
        }
        MesherMetrics.recordSectionFailure();
        MesherMetrics.recordCircuitBreakerTrip();
        MesherMetrics.recordDecision(FastPathCriteria.Result.fallback(FastPathCriteria.Reason.TRANSLUCENT_LAYER));
        MesherMetrics.recordDecision(FastPathCriteria.Result.admittedWeighted());
        MesherMetrics.Snapshot after = MesherMetrics.snapshot();
        if (after.meshFastPathFailures() != 1L || after.meshFastPathCircuitBreakerTrips() != 1L) {
            throw new AssertionError("fail-open / circuit-breaker counters");
        }
        if (after.fallbackCount(FastPathCriteria.Reason.TRANSLUCENT_LAYER) != 1L) {
            throw new AssertionError("coverage bucket for translucent");
        }
        if (after.weightedFastPathBlocks() != 1L) {
            throw new AssertionError("weighted fast-path counter");
        }
        if (after.fastPathCoverageOfNonAir() <= 0.0) {
            throw new AssertionError("coverage ratio must be exported");
        }
        StringBuilder json = new StringBuilder();
        after.appendJson(json);
        if (!json.toString().contains("meshFastPathFailures")
                || !json.toString().contains("fastPathCoverageOfNonAir")
                || !json.toString().contains("TRANSLUCENT_LAYER")
                || !json.toString().contains("weightedFastPathBlocks")
                || !json.toString().contains("WEIGHTED_NON_CUBE")
                || !json.toString().contains("MULTIPART_MODEL")
                || !json.toString().contains("\"scene\"")) {
            throw new AssertionError("mesher JSON must export coverage, scene, weighted cubes, and fail-open counters");
        }
        pass("mesher_metrics");
    }

    private static void testCpuMeshingTime() {
        int[][][] solid = SectionFixtures.emptyHalo();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    SectionFixtures.setInterior(solid, x, y, z, SectionFixtures.FULL_CUBE);
                }
            }
        }
        int[][][] checker = SectionFixtures.emptyHalo();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (((x + y + z) & 1) == 0) {
                        SectionFixtures.setInterior(checker, x, y, z, SectionFixtures.FULL_CUBE);
                    }
                }
            }
        }
        CpuMeshingBenchmark.Sample solidSample = CpuMeshingBenchmark.run(
                "solid_16cubed", SectionFixtures.pack(0, 0, 0, solid), 40);
        CpuMeshingBenchmark.Sample checkerSample = CpuMeshingBenchmark.run(
                "checkerboard", SectionFixtures.pack(0, 0, 0, checker), 40);
        CpuMeshingBenchmark.Sample weightedSample = CpuMeshingBenchmark.run(
                "weighted_overworld_volume",
                SectionFixtures.pack(0, 0, 0, MesherWorldFixtures.weightedOverworldVolume()),
                40);
        SnapshotFlagHotPath.Sample flags = SnapshotFlagHotPath.run(80);
        System.out.println(CpuMeshingBenchmark.format(solidSample));
        System.out.println(CpuMeshingBenchmark.format(checkerSample));
        System.out.println(CpuMeshingBenchmark.format(weightedSample));
        System.out.println(SnapshotFlagHotPath.format(flags));
        if (solidSample.vertices() <= 0 || checkerSample.vertices() <= 0 || weightedSample.vertices() <= 0) {
            throw new AssertionError("CPU benchmark produced empty meshes");
        }
        if (flags.leanNs() <= 0L || flags.heavyNs() <= 0L) {
            throw new AssertionError("snapshot flag hot-path bench produced no samples");
        }
        if (flags.leanNs() > flags.heavyNs() * 2L) {
            throw new AssertionError("lean production flagsOf must not be slower than the removed 6-face walk");
        }
        pass("cpu_meshing_time_not_a_perf_claim");
    }

    private static void testWeightedAndPerStateCubeEquivalence() {
        int[][][] isolated = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(isolated, 8, 8, 8, SectionFixtures.WEIGHTED_CUBE);
        assertGeometry("isolated_weighted_cube", 0, 64, 0, isolated, 6 * 4);

        int[][][] facing = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(facing, 3, 3, 3, SectionFixtures.FACING_CUBE);
        SectionFixtures.setInterior(facing, 4, 3, 3, SectionFixtures.FACING_CUBE);
        assertGeometry("adjacent_facing_cubes", 0, 64, 0, facing, 10 * 4);

        int[][][] axis = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(axis, 5, 5, 5, SectionFixtures.AXIS_CUBE);
        SectionFixtures.setInterior(axis, 6, 5, 5, SectionFixtures.WEIGHTED_CUBE);
        assertGeometry("axis_cube_next_to_weighted_stone", 0, 64, 0, axis, 10 * 4);

        int[][][] mixed = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(mixed, 2, 2, 2, SectionFixtures.WEIGHTED_CUBE);
        SectionFixtures.setInterior(mixed, 4, 2, 2, SectionFixtures.AXIS_CUBE);
        SectionFixtures.setInterior(mixed, 6, 2, 2, SectionFixtures.FACING_CUBE);
        SectionFixtures.setInterior(mixed, 8, 2, 2, SectionFixtures.FULL_CUBE);
        PackedSectionVolume volume = SectionFixtures.pack(0, 64, 0, mixed);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        assertIdentical("per_state_and_weighted_cubes", meshOracle(volume), fast.vertices());
        if (fast.fastPathBlocks() != 4) {
            throw new AssertionError("four distinct cube BlockState fixtures must all fast-path, got "
                    + fast.fastPathBlocks());
        }
        pass("weighted_and_per_state_cube_equivalence");
    }

    private static void testGrassOverlayAndMultipartStayFallback() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 4, 4, 4, SectionFixtures.GRASS_OVERLAY);
        SectionFixtures.setInterior(states, 6, 4, 4, SectionFixtures.FENCE);
        SectionFixtures.setInterior(states, 8, 4, 4, SectionFixtures.RANDOM);
        PackedSectionVolume volume = SectionFixtures.pack(0, 64, 0, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        if (!fast.vertices().isEmpty() || fast.fastPathBlocks() != 0 || fast.fallbackBlocks() != 3) {
            throw new AssertionError("grass overlay / multipart / weighted non-cube must stay vanilla fallback");
        }
        expectCriteria(SectionFixtures.GRASS_OVERLAY, false, FastPathCriteria.Reason.WEIGHTED_NON_CUBE);
        expectCriteria(SectionFixtures.FENCE, false, FastPathCriteria.Reason.MULTIPART_MODEL);
        pass("grass_overlay_and_multipart_fallback");
    }

    private static void testVanillaWeightedPickMatchesVanillaRng() {
        WeightedList<String> list = WeightedList.of("north", "east", "south", "west");
        RandomSource expectedRng = RandomSource.createThreadLocalInstance(0L);
        RandomSource actualRng = VanillaWeightedPick.mesherRandom();
        long[] seeds = {
                0L,
                1L,
                VanillaBlockSeed.defaultSeed(3, 64, -8),
                VanillaBlockSeed.defaultSeed(-32, 12, 48),
                Long.MIN_VALUE,
                42L
        };
        for (long seed : seeds) {
            expectedRng.setSeed(seed);
            String expected = list.getRandomOrThrow(expectedRng);
            String actual = VanillaWeightedPick.pick(list, actualRng, seed);
            if (!expected.equals(actual)) {
                throw new AssertionError("weighted pick mismatch at seed " + seed + ": " + expected + " vs " + actual);
            }
        }
        Set<String> seen = new HashSet<>();
        for (int x = 0; x < 64; x++) {
            seen.add(VanillaWeightedPick.pick(list, actualRng, VanillaBlockSeed.defaultSeed(x, 70, 0)));
        }
        if (seen.size() < 2) {
            throw new AssertionError("vanilla weighted pick must vary across BlockPos seeds, got " + seen);
        }
        pass("vanilla_weighted_pick_matches_rng");
    }

    private static void testVanillaCubeCoverageCatalog() {
        if (VanillaCubeCoverage.WEIGHTED_UNIT_CUBE_BLOCKS.size() != 29) {
            throw new AssertionError("26.2 weighted unit-cube catalog size");
        }
        for (String required : List.of("stone", "dirt", "deepslate", "sand", "netherrack", "bedrock")) {
            if (!VanillaCubeCoverage.WEIGHTED_UNIT_CUBE_BLOCKS.contains(required)) {
                throw new AssertionError("weighted unit-cube catalog missing " + required);
            }
        }
        if (VanillaCubeCoverage.WEIGHTED_NON_CUBE_BLOCKS.contains("stone")
                || VanillaCubeCoverage.WEIGHTED_UNIT_CUBE_BLOCKS.contains("grass_block")) {
            throw new AssertionError("stone must be weighted-cube; grass_block overlay must not");
        }
        if (!VanillaCubeCoverage.PER_STATE_SINGLE_VARIANT_CUBE_EXAMPLES.contains("furnace")
                || !VanillaCubeCoverage.PER_STATE_SINGLE_VARIANT_CUBE_EXAMPLES.contains("oak_log")) {
            throw new AssertionError("facing/axis cubes are already per-BlockState SingleVariant");
        }
        if (!VanillaCubeCoverage.MULTIPART_EXAMPLES.contains("bamboo")
                || !VanillaCubeCoverage.MULTIPART_EXAMPLES.contains("oak_fence")) {
            throw new AssertionError("multipart catalog must keep bamboo/fences as fallback");
        }
        pass("vanilla_cube_coverage_catalog");
    }

    private static void expectCriteria(final int stateId, final boolean fast, final FastPathCriteria.Reason reason) {
        FastPathCriteria.Result result = FastPathCriteria.fromFixtureState(stateId);
        if (result.fastPath() != fast || result.reason() != reason) {
            throw new AssertionError("criteria for " + stateId + ": expected fast=" + fast + " reason=" + reason
                    + " got " + result);
        }
    }

    private static List<MeshEquivalence.TerrainVertex> assertGeometry(
            final String name,
            final int originX,
            final int originY,
            final int originZ,
            final int[][][] states,
            final int expectedVertices) {
        return assertGeometry(name, SectionFixtures.pack(originX, originY, originZ, states), expectedVertices);
    }

    private static List<MeshEquivalence.TerrainVertex> assertGeometry(
            final String name,
            final PackedSectionVolume volume,
            final int expectedVertices) {
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        List<MeshEquivalence.TerrainVertex> oracle = meshOracle(volume);
        assertIdentical(name, oracle, fast.vertices());
        if (fast.vertices().size() != expectedVertices) {
            throw new AssertionError(name + " expected " + expectedVertices + " vertices, got " + fast.vertices().size());
        }
        return fast.vertices();
    }

    private static void assertIdentical(
            final String name,
            final List<MeshEquivalence.TerrainVertex> oracle,
            final List<MeshEquivalence.TerrainVertex> fast) {
        MeshEquivalence.Result result = MeshEquivalence.compareExactGeometry(oracle, fast);
        if (result != MeshEquivalence.Result.IDENTICAL_ORDERED) {
            throw new AssertionError(name + " geometry mismatch: " + describeMismatch(oracle, fast));
        }
        pass(name);
    }

    private static String describeMismatch(
            final List<MeshEquivalence.TerrainVertex> oracle,
            final List<MeshEquivalence.TerrainVertex> fast) {
        if (oracle.size() != fast.size()) {
            return "size oracle=" + oracle.size() + " fast=" + fast.size();
        }
        for (int i = 0; i < oracle.size(); i++) {
            if (!oracle.get(i).equals(fast.get(i))) {
                return "vertex " + i + " oracle=" + oracle.get(i) + " fast=" + fast.get(i);
            }
        }
        return "unknown";
    }

    private static FastPathCubeMesher.MeshResult meshFast(final PackedSectionVolume volume) {
        PackedLightVolume lights = new PackedLightVolume();
        lights.fill(LightCoordsUtil.pack(15, 15));
        return FastPathCubeMesher.mesh(volume, lights, true, CardinalLighting.DEFAULT);
    }

    private static List<MeshEquivalence.TerrainVertex> meshOracle(final PackedSectionVolume volume) {
        PackedLightVolume lights = new PackedLightVolume();
        lights.fill(LightCoordsUtil.pack(15, 15));
        return VanillaCubeOracle.mesh(volume, lights, true, CardinalLighting.DEFAULT);
    }

    private static List<MeshVisit> assertPlansMatch(
            final int originX,
            final int originY,
            final int originZ,
            final int[][][] states) {
        int[][][] flags = SectionFixtures.flagsOf(states);
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

    private static void testSectionFailOpenDoesNotCrashOtherSections() {
        MesherMetrics.reset();
        java.util.List<String> compiled = new java.util.ArrayList<>();
        MesherSectionFailOpen.Outcome<String> failed = MesherSectionFailOpen.compile(
                "section[0,4,0]",
                () -> {
                    compiled.add("hybrid-A");
                    throw new IllegalStateException("intentional fast-path fault");
                },
                () -> {
                    compiled.add("vanilla-A");
                    return "mesh-vanilla-A";
                });
        if (failed.hybridSucceeded() || !"mesh-vanilla-A".equals(failed.value())) {
            throw new AssertionError("faulted section must use vanilla mesh, got " + failed);
        }
        if (!compiled.equals(java.util.List.of("hybrid-A", "vanilla-A"))) {
            throw new AssertionError("vanilla fallback must run after hybrid throw: " + compiled);
        }
        MesherSectionFailOpen.Outcome<String> neighbor = MesherSectionFailOpen.compile(
                "section[1,4,0]",
                () -> {
                    compiled.add("hybrid-B");
                    return "mesh-hybrid-B";
                },
                () -> {
                    compiled.add("vanilla-B");
                    return "mesh-vanilla-B";
                });
        if (!neighbor.hybridSucceeded() || !"mesh-hybrid-B".equals(neighbor.value())) {
            throw new AssertionError("neighbor section must stay on hybrid: " + neighbor);
        }
        if (compiled.contains("vanilla-B")) {
            throw new AssertionError("neighbor section must not be forced to vanilla");
        }
        if (MesherMetrics.snapshot().meshFastPathFailures() != 1L) {
            throw new AssertionError("exactly one section fail-open must be counted");
        }
        pass("section_fail_open_isolated");
    }

    private static void testCircuitBreakerIsPerBlockStateNotSection() {
        MesherCircuitBreaker breaker = MesherCircuitBreaker.get();
        breaker.reset();
        Object glassLike = new Object();
        Object stone = new Object();
        for (int i = 0; i < MesherCircuitBreaker.TRIP_AFTER; i++) {
            if (breaker.isOpen(glassLike)) {
                throw new AssertionError("breaker must not trip before threshold");
            }
            breaker.recordFailure(glassLike);
        }
        if (!breaker.isOpen(glassLike)) {
            throw new AssertionError("breaker must trip the noisy BlockState");
        }
        if (breaker.isOpen(stone) || !breaker.allowFastPath(stone)) {
            throw new AssertionError("a different BlockState must keep the fast path");
        }
        int trips = breaker.tripCount();
        breaker.recordFailure(glassLike);
        if (breaker.tripCount() != trips) {
            throw new AssertionError("already-open key must not spam additional trips");
        }
        pass("circuit_breaker_per_blockstate");
    }

    private static void testGlassAndTranslucentAlwaysFallback() {
        expectCriteria(SectionFixtures.TRANSPARENT, false, FastPathCriteria.Reason.TRANSLUCENT_LAYER);
        if (SectionFixtures.fixtureAllowsFastPath(SectionFixtures.TRANSPARENT)) {
            throw new AssertionError("glass fixture must not be on the fast-path whitelist");
        }
        if (!BlockRenderFlags.translucent(SectionFixtures.flags(SectionFixtures.TRANSPARENT))) {
            throw new AssertionError("glass fixture flags must carry TRANSLUCENT");
        }
        if (FastPathCriteria.fromFlags(SectionFixtures.flags(SectionFixtures.TRANSPARENT)).fastPath()) {
            throw new AssertionError("fromFlags must reject translucent glass");
        }
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 4, 4, 4, SectionFixtures.TRANSPARENT);
        FastPathCubeMesher.MeshResult fast = meshFast(SectionFixtures.pack(0, 64, 0, states));
        if (!fast.vertices().isEmpty() || fast.fastPathBlocks() != 0 || fast.fallbackBlocks() != 1) {
            throw new AssertionError("isolated glass must be vanilla fallback only");
        }
        pass("glass_translucent_always_fallback");
    }

    private static void testAnimatedCubeUvsAreFrameIndependent() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 3, 3, 3, SectionFixtures.ANIMATED);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        FastPathCubeMesher.MeshResult frameA = meshFast(volume);
        FastPathCubeMesher.MeshResult frameB = meshFast(volume);
        assertIdentical("animated_texture_uv_stable", frameA.vertices(), frameB.vertices());
        FullCubeTemplates.CubeMaterial magma = FullCubeTemplates.CubeMaterial.animatedMagma();
        if (frameA.vertices().isEmpty()) {
            throw new AssertionError("animated cube fixture must still be a fast-path opaque cube");
        }
        boolean sawSpriteRect = frameA.vertices().stream().anyMatch(v -> v.u() == magma.u0() || v.u() == magma.u0() + magma.uSpan());
        if (!sawSpriteRect) {
            throw new AssertionError("animated cube UVs must be the sprite rectangle, not a frame index");
        }
    }

    private static void testMultipleTintProviders() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 2, 2, 2, SectionFixtures.TINT);
        SectionFixtures.setInterior(states, 4, 2, 2, SectionFixtures.TINT_FOLIAGE);
        SectionFixtures.setInterior(states, 6, 2, 2, SectionFixtures.TINT_WATER);
        PackedSectionVolume volume = SectionFixtures.pack(0, 0, 0, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        List<MeshEquivalence.TerrainVertex> oracle = meshOracle(volume);
        assertIdentical("multiple_tint_providers", oracle, fast.vertices());
        long distinctColors = fast.vertices().stream().map(v -> v.color()).distinct().count();
        if (distinctColors < 3) {
            throw new AssertionError("grass/foliage/water tint providers must not collapse to one color, got "
                    + distinctColors);
        }
    }

    private static void testUnloadedNeighborSectionEdge() {
        int[][][] states = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(states, 0, 0, 0, SectionFixtures.FULL_CUBE);
        PackedSectionVolume volume = SectionFixtures.pack(16, 64, -32, states);
        FastPathCubeMesher.MeshResult fast = meshFast(volume);
        if (fast.vertices().size() != 6 * 4) {
            throw new AssertionError("unloaded neighbor (air halo) must keep all six faces, got "
                    + fast.vertices().size());
        }
        assertGeometry("unloaded_neighbor_section_edge", volume, 6 * 4);
    }

    private static void testCompileAfterReloadIsFreshNotStale() {
        Object generationA = new Object();
        Object generationB = new Object();
        CubeModelCache cache = HybridSectionMesher.bindWorkerCubeCache(generationA);
        seedCacheOccupant(cache);
        if (cache.isEmpty()) {
            throw new AssertionError("pre-reload cache must be occupied");
        }
        CubeModelCache after = HybridSectionMesher.bindWorkerCubeCache(generationB);
        if (after != cache || !after.isEmpty()) {
            throw new AssertionError("reload must empty the same worker cache");
        }
        seedCacheOccupant(after);
        if (after.cachedEntryCount() != 1) {
            throw new AssertionError("next compile after reload must populate fresh entries, not stay empty");
        }
        after.bindModelSet(generationB);
        if (after.cachedEntryCount() != 1) {
            throw new AssertionError("same generation must keep the fresh compile result");
        }
        int[][][] before = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(before, 5, 5, 5, SectionFixtures.FULL_CUBE);
        int[][][] afterReload = SectionFixtures.emptyHalo();
        SectionFixtures.setInterior(afterReload, 5, 5, 5, SectionFixtures.ANIMATED);
        FastPathCubeMesher.MeshResult staleStone = meshFast(SectionFixtures.pack(0, 0, 0, before));
        FastPathCubeMesher.MeshResult freshAnimated = meshFast(SectionFixtures.pack(0, 0, 0, afterReload));
        if (freshAnimated.vertices().isEmpty()) {
            throw new AssertionError("compile after reload must emit the new section, not an empty mesh");
        }
        assertIdentical("compile_after_reload_fresh_geometry", meshOracle(SectionFixtures.pack(0, 0, 0, afterReload)), freshAnimated.vertices());
        boolean reusedStoneUv = freshAnimated.vertices().stream().allMatch(v ->
                staleStone.vertices().stream().anyMatch(old -> old.u() == v.u() && old.v() == v.v()));
        if (reusedStoneUv) {
            throw new AssertionError("reload compile must not reuse the previous atlas rectangle");
        }
        pass("compile_after_reload_fresh");
    }

    private static void testRealisticCpuDatasets() {
        CpuMeshingBenchmark.Sample typical = CpuMeshingBenchmark.run(
                "typical_overworld_surface",
                SectionFixtures.pack(0, 64, 0, MesherWorldFixtures.typicalOverworldSurface()),
                20);
        CpuMeshingBenchmark.Sample dense = CpuMeshingBenchmark.run(
                "dense_structure",
                SectionFixtures.pack(0, 64, 0, MesherWorldFixtures.denseStructure()),
                20);
        CpuMeshingBenchmark.Sample weighted = CpuMeshingBenchmark.run(
                "weighted_overworld_volume",
                SectionFixtures.pack(0, 64, 0, MesherWorldFixtures.weightedOverworldVolume()),
                20);
        System.out.println(CpuMeshingBenchmark.format(typical));
        System.out.println(CpuMeshingBenchmark.format(dense));
        System.out.println(CpuMeshingBenchmark.format(weighted));
        if (typical.fastPathBlocks() <= 0 || dense.fastPathBlocks() <= 0 || weighted.fastPathBlocks() <= 0) {
            throw new AssertionError("realistic datasets must still have some fast-path cubes");
        }
        if (weighted.fastPathBlocks() <= typical.fastPathBlocks()) {
            throw new AssertionError("weighted underground dataset should admit more cubes than the mixed surface");
        }
        pass("cpu_meshing_realistic_datasets_not_a_perf_claim");
    }

    private static void testMesherBenchmarkScenes() {
        if (MesherBenchmarkScenes.all().size() != 3) {
            throw new AssertionError("three mesher hardware scenes are required");
        }
        MesherBenchmarkScenes.byId("mesher_cold_load");
        MesherBenchmarkScenes.byId("mesher_rebuild_storm");
        MesherBenchmarkScenes.byId("mesher_chunk_flight");
        if (!MesherBenchmarkScenes.HARDWARE_METRICS.contains("mesherMetrics.fastPathCoverageOfNonAir")
                || !MesherBenchmarkScenes.HARDWARE_METRICS.contains("mesherMetrics.weightedFastPathBlocks")) {
            throw new AssertionError("hardware metric list must include real-world coverage");
        }
        if (ClientFrameBenchmark.warmupFramesForScene("mesher_cold_load") != 60
                || ClientFrameBenchmark.sampleFramesForScene("mesher_cold_load") != 2400) {
            throw new AssertionError("cold-load scene must use the short warmup/sample window");
        }
        if (ClientFrameBenchmark.warmupFramesForScene("mesher_rebuild_storm") != 600) {
            throw new AssertionError("rebuild-storm scene must use the 600-frame warmup");
        }
        if (!"chunk_flight".equals(ClientFrameBenchmark.cameraModeForScene("mesher_chunk_flight"))) {
            throw new AssertionError("chunk-flight scene must default the camera to chunk_flight");
        }
        if (!"stationary".equals(ClientFrameBenchmark.cameraModeForScene("mesher_cold_load"))) {
            throw new AssertionError("cold-load scene stays stationary");
        }
        pass("mesher_benchmark_scenes");
    }

    private static void testCubeCacheInvalidatesOnModelSetReload() {
        CubeModelCache cache = new CubeModelCache();
        Object generationA = new Object();
        Object generationB = new Object();
        cache.bindModelSet(generationA);
        seedCacheOccupant(cache);
        if (cache.isEmpty() || cache.cachedEntryCount() == 0) {
            throw new AssertionError("seeded cubeCache must be occupied");
        }
        if (cache.boundModelSet() != generationA) {
            throw new AssertionError("cubeCache must remember the bound model-set identity");
        }
        cache.bindModelSet(generationA);
        if (cache.isEmpty() || cache.cachedEntryCount() == 0) {
            throw new AssertionError("same BlockStateModelSet identity must keep cached quads");
        }
        cache.bindModelSet(generationB);
        if (!cache.isEmpty() || cache.cachedEntryCount() != 0) {
            throw new AssertionError("resource reload must clear cubeCache");
        }
        if (cache.boundModelSet() != generationB) {
            throw new AssertionError("cubeCache must rebind to the new model-set identity");
        }
        cache.bindModelSet(generationB);
        if (!cache.isEmpty()) {
            throw new AssertionError("rebind to the same generation must not resurrect entries");
        }

        Object workerA = new Object();
        Object workerB = new Object();
        CubeModelCache worker = HybridSectionMesher.bindWorkerCubeCache(workerA);
        seedCacheOccupant(worker);
        if (worker.isEmpty()) {
            throw new AssertionError("worker ThreadLocal cubeCache must keep entries for one model set");
        }
        CubeModelCache sameWorker = HybridSectionMesher.bindWorkerCubeCache(workerA);
        if (sameWorker != worker || sameWorker.isEmpty()) {
            throw new AssertionError("same model-set identity must reuse the occupied worker cache");
        }
        CubeModelCache reloaded = HybridSectionMesher.bindWorkerCubeCache(workerB);
        if (reloaded != worker) {
            throw new AssertionError("reload must clear the existing worker cache, not allocate a different one");
        }
        if (!reloaded.isEmpty() || reloaded.cachedEntryCount() != 0) {
            throw new AssertionError("F3+T / resource reload must empty the worker ThreadLocal cubeCache");
        }
        pass("cube_cache_resource_reload");
    }

    @SuppressWarnings("unchecked")
    private static void seedCacheOccupant(final CubeModelCache cache) {
        try {
            var misses = CubeModelCache.class.getDeclaredField("misses");
            misses.setAccessible(true);
            ((Map<Object, Object>) misses.get(cache)).put(new Object(), FastPathCriteria.Reason.NOT_SINGLE_VARIANT);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not seed cubeCache occupant", e);
        }
    }

    private static void pass(final String name) {
        CASE_RESULTS.add("PASS  " + name);
    }
}
