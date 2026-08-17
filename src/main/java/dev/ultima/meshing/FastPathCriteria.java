package dev.ultima.meshing;

/**
 * Single source of truth for hybrid-mesher admission.
 *
 * <p>Production ({@code CubeModelCache}/{@code HybridSectionMesher}) and the
 * synthetic kernel ({@link #fromFixtureState}) must agree on this tree. Flag
 * bits alone are a fixture heuristic ({@link #fromFlags}); they do not see
 * model class or render layer. Translucent / glass blocks are
 * <strong>always fallback</strong> in this version (sorting / shader /
 * {@code HalfTransparentBlock} risk).
 *
 * <p>Covered (fast path) when every check passes:
 * <ul>
 *   <li>Vanilla {@code SingleVariant} whose baked part is exactly one
 *       axis-aligned opaque unit-cube quad per {@code Direction} and no
 *       unculled quads. The cache is keyed by {@code BlockState} identity, so
 *       furnace facing, log axis, and other property cubes are distinct
 *       entries of the <em>same</em> geometric rule.</li>
 *   <li>Vanilla {@code WeightedVariants} whose <em>every</em> alternative is
 *       such a {@code SingleVariant} unit cube (26.2 stone/dirt/deepslate/sand
 *       random rotations). The alternative is picked with vanilla
 *       {@code BlockState.getSeed(pos)} + {@code WeightedList.getRandomOrThrow}.</li>
 *   <li>Neighbor occlusion via vanilla {@code Block.shouldRenderFace}.</li>
 *   <li>Lighting/AO/tint via vanilla {@code BlockModelLighter} and
 *       {@code BlockColors} on those cached quads.</li>
 * </ul>
 *
 * <p>Always fallback:
 * <ul>
 *   <li>Air (skipped, matching vanilla).</li>
 *   <li>Fluids and waterlogged fluid tessellation.</li>
 *   <li>Non-{@code RenderShape.MODEL} blocks.</li>
 *   <li>Any quad on {@code ChunkSectionLayer.TRANSLUCENT} (glass, stained
 *       glass, ice, slime, honey, tinted glass).</li>
 *   <li>{@code MultiPartModel} (fences, bamboo, redstone, mushrooms).</li>
 *   <li>Weighted families that are not 6-quad cubes (grass overlay, dirt path).</li>
 *   <li>Non-unit-cube geometry (stairs, slabs, plants, rails, chests).</li>
 *   <li>Offset functions (flowers, ferns).</li>
 *   <li>{@code LeavesBlock} / {@code forceOpaque}.</li>
 *   <li>Circuit-breaker tripped BlockState (session, that model only).</li>
 * </ul>
 */
public final class FastPathCriteria {
    public enum Decision {
        FAST_PATH,
        VANILLA_FALLBACK
    }

    public enum Reason {
        FAST_PATH_UNIT_CUBE("opaque unit-cube SingleVariant for this BlockState; geometry from cached vanilla quads"),
        FAST_PATH_WEIGHTED_UNIT_CUBE("opaque unit-cube WeightedVariants; pick with vanilla BlockState seed"),
        AIR("air is skipped, matching vanilla SectionCompiler"),
        HAS_FLUID("fluid tessellation always uses FluidRenderer"),
        NOT_MODEL("RenderShape is not MODEL"),
        HAS_OFFSET("block offset function is non-null"),
        SKIP_RENDERING("LeavesBlock / forceOpaque; never fast-pathed"),
        TRANSLUCENT_LAYER("translucent render layer (glass/ice/slime); always vanilla"),
        NOT_SINGLE_VARIANT("model is not a seed-stable or weighted unit-cube family"),
        WEIGHTED_NON_CUBE("WeightedVariants whose alternatives are not all 6-quad unit cubes"),
        MULTIPART_MODEL("multipart / connected-state model; always vanilla"),
        UNCULLED_QUADS("model emits unculled quads"),
        FACE_QUAD_COUNT("a culled face does not have exactly one quad"),
        NOT_UNIT_CUBE_FACE("a culled quad is not an axis-aligned unit-cube face"),
        FACE_DIRECTION_MISMATCH("baked quad direction does not match the cull face"),
        CIRCUIT_BREAKER("this BlockState tripped the session circuit breaker"),
        SECTION_FAIL_OPEN("whole section failed open to vanilla SectionCompiler"),
        COMPLEX_NEIGHBOR_OCCLUSION("test-only: neighbor occlusion is not empty or full-block");

        private final String detail;

        Reason(final String detail) {
            this.detail = detail;
        }

        public String detail() {
            return this.detail;
        }

        public boolean countsAsNonAirCompile() {
            return this != AIR;
        }

        public boolean fastPath() {
            return this == FAST_PATH_UNIT_CUBE || this == FAST_PATH_WEIGHTED_UNIT_CUBE;
        }
    }

    public record Result(Decision decision, Reason reason) {
        public boolean fastPath() {
            return this.decision == Decision.FAST_PATH;
        }

        public static Result admitted() {
            return new Result(Decision.FAST_PATH, Reason.FAST_PATH_UNIT_CUBE);
        }

        public static Result admittedWeighted() {
            return new Result(Decision.FAST_PATH, Reason.FAST_PATH_WEIGHTED_UNIT_CUBE);
        }

        public static Result fallback(final Reason reason) {
            return new Result(Decision.VANILLA_FALLBACK, reason);
        }
    }

    private FastPathCriteria() {
    }

    /**
     * Coarse fixture-flag heuristic. Does <em>not</em> encode translucency or
     * model class. Production must not use this to admit a cell; use
     * {@link #fromFixtureState} in tests and {@code CubeModelCache.lookup} in
     * game. Occlusion / {@code skipRendering} bits on flags are test-fixture
     * only (see {@code RenderSectionSnapshot.flagsOfForTest}).
     */
    public static Result fromFlags(final int flags) {
        if (BlockRenderFlags.air(flags)) {
            return Result.fallback(Reason.AIR);
        }
        if (BlockRenderFlags.hasFluid(flags)) {
            return Result.fallback(Reason.HAS_FLUID);
        }
        if (!BlockRenderFlags.model(flags)) {
            return Result.fallback(Reason.NOT_MODEL);
        }
        if (BlockRenderFlags.skipRendering(flags)) {
            return Result.fallback(Reason.SKIP_RENDERING);
        }
        if (BlockRenderFlags.translucent(flags)) {
            return Result.fallback(Reason.TRANSLUCENT_LAYER);
        }
        return Result.admitted();
    }

    public static Result fromFixtureState(final int stateId) {
        if (stateId == SectionFixtures.WEIGHTED_CUBE) {
            return Result.admittedWeighted();
        }
        if (SectionFixtures.fixtureAllowsFastPath(stateId)) {
            Result fromFlags = fromFlags(SectionFixtures.flags(stateId));
            if (fromFlags.fastPath()) {
                return fromFlags;
            }
        }
        return switch (stateId) {
            case SectionFixtures.AIR -> Result.fallback(Reason.AIR);
            case SectionFixtures.FLUID -> Result.fallback(Reason.HAS_FLUID);
            case SectionFixtures.LEAVES -> Result.fallback(Reason.SKIP_RENDERING);
            case SectionFixtures.TRANSPARENT -> Result.fallback(Reason.TRANSLUCENT_LAYER);
            case SectionFixtures.RANDOM, SectionFixtures.GRASS_OVERLAY -> Result.fallback(Reason.WEIGHTED_NON_CUBE);
            case SectionFixtures.FENCE -> Result.fallback(Reason.MULTIPART_MODEL);
            case SectionFixtures.STAIRS, SectionFixtures.SLAB, SectionFixtures.PLANT, SectionFixtures.CUTOUT ->
                    Result.fallback(Reason.NOT_UNIT_CUBE_FACE);
            case SectionFixtures.BLOCK_ENTITY -> Result.admitted();
            default -> fromFlags(SectionFixtures.flags(stateId));
        };
    }
}
