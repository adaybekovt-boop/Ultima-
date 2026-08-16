package dev.ultima.meshing;

/**
 * Decision tree for the hybrid mesher. If any check fails, the cell uses the
 * vanilla {@code ModelBlockRenderer}/{@code FluidRenderer} path. Ultima never
 * approximates a case it cannot prove identical.
 *
 * <p>Covered (fast path) when every check passes:
 * <ul>
 *   <li>Vanilla {@code SingleVariant} models whose baked part is exactly one
 *       axis-aligned unit-cube quad per {@code Direction} and no unculled quads
 *       (typical {@code cube}/{@code cube_all}/{@code cube_bottom_top}/
 *       {@code cube_column} full cubes: stone, dirt, ores, planks, wool,
 *       concrete, terracotta, sand, gravel, netherrack, glowstone, glass,
 *       ice, grass_block if its model stays a 6-quad cube).</li>
 *   <li>Standard neighbor occlusion via vanilla {@code Block.shouldRenderFace}
 *       (full-block occluder identity, empty occluder, or the exact voxel
 *       join). No simplified occupancy guess in production.</li>
 *   <li>Lighting/AO/tint via vanilla {@code BlockModelLighter} and
 *       {@code BlockColors} on those cached quads.</li>
 * </ul>
 *
 * <p>Always fallback:
 * <ul>
 *   <li>Air (skipped entirely, matching vanilla).</li>
 *   <li>Fluids and waterlogged fluid tessellation.</li>
 *   <li>Non-{@code RenderShape.MODEL} blocks.</li>
 *   <li>{@code WeightedVariants}, multipart, and any model that is not
 *       {@code SingleVariant} (random mushrooms, fences, etc.).</li>
 *   <li>Any model that is not exactly six unit-cube culled quads (stairs,
 *       slabs, plants, rails, chests, custom/modded geometry).</li>
 *   <li>Blocks with an offset function (flowers, grass plants).</li>
 *   <li>{@code LeavesBlock} (fast-graphics {@code forceOpaque} layer remap, and
 *       fancy-leaf models are not assumed to stay 6-quad cubes).</li>
 *   <li>Fabric custom models that are not a vanilla {@code SingleVariant}.</li>
 * </ul>
 */
public final class FastPathCriteria {
    public enum Decision {
        FAST_PATH,
        VANILLA_FALLBACK
    }

    public enum Reason {
        FAST_PATH_UNIT_CUBE("simple unit-cube SingleVariant; geometry from cached vanilla quads"),
        AIR("air is skipped, matching vanilla SectionCompiler"),
        HAS_FLUID("fluid tessellation always uses FluidRenderer"),
        NOT_MODEL("RenderShape is not MODEL"),
        HAS_OFFSET("block offset function is non-null"),
        SKIP_RENDERING("LeavesBlock / forceOpaque; never fast-pathed"),
        NOT_SINGLE_VARIANT("model is not vanilla SingleVariant (weighted/multipart/modded)"),
        UNCULLED_QUADS("model emits unculled quads"),
        FACE_QUAD_COUNT("a culled face does not have exactly one quad"),
        NOT_UNIT_CUBE_FACE("a culled quad is not an axis-aligned unit-cube face"),
        FACE_DIRECTION_MISMATCH("baked quad direction does not match the cull face"),
        COMPLEX_NEIGHBOR_OCCLUSION("test-only: neighbor occlusion is not empty or full-block");

        private final String detail;

        Reason(final String detail) {
            this.detail = detail;
        }

        public String detail() {
            return this.detail;
        }
    }

    public record Result(Decision decision, Reason reason) {
        public boolean fastPath() {
            return this.decision == Decision.FAST_PATH;
        }
    }

    private FastPathCriteria() {
    }

    public static Result fromFlags(final int flags) {
        if (BlockRenderFlags.air(flags)) {
            return new Result(Decision.VANILLA_FALLBACK, Reason.AIR);
        }
        if (BlockRenderFlags.hasFluid(flags)) {
            return new Result(Decision.VANILLA_FALLBACK, Reason.HAS_FLUID);
        }
        if (!BlockRenderFlags.model(flags)) {
            return new Result(Decision.VANILLA_FALLBACK, Reason.NOT_MODEL);
        }
        if (BlockRenderFlags.skipRendering(flags)) {
            return new Result(Decision.VANILLA_FALLBACK, Reason.SKIP_RENDERING);
        }
        return new Result(Decision.FAST_PATH, Reason.FAST_PATH_UNIT_CUBE);
    }

    public static Result fromFixtureState(final int stateId) {
        if (SectionFixtures.fixtureAllowsFastPath(stateId)) {
            Result fromFlags = fromFlags(SectionFixtures.flags(stateId));
            if (fromFlags.fastPath()) {
                return fromFlags;
            }
        }
        return switch (stateId) {
            case SectionFixtures.AIR -> new Result(Decision.VANILLA_FALLBACK, Reason.AIR);
            case SectionFixtures.FLUID -> new Result(Decision.VANILLA_FALLBACK, Reason.HAS_FLUID);
            case SectionFixtures.LEAVES -> new Result(Decision.VANILLA_FALLBACK, Reason.SKIP_RENDERING);
            case SectionFixtures.RANDOM -> new Result(Decision.VANILLA_FALLBACK, Reason.NOT_SINGLE_VARIANT);
            case SectionFixtures.STAIRS, SectionFixtures.SLAB, SectionFixtures.FENCE, SectionFixtures.PLANT, SectionFixtures.CUTOUT ->
                    new Result(Decision.VANILLA_FALLBACK, Reason.NOT_UNIT_CUBE_FACE);
            case SectionFixtures.BLOCK_ENTITY -> new Result(Decision.FAST_PATH, Reason.FAST_PATH_UNIT_CUBE);
            default -> fromFlags(SectionFixtures.flags(stateId));
        };
    }
}
