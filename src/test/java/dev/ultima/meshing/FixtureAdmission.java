package dev.ultima.meshing;

import dev.ultima.meshing.FastPathCriteria.Reason;
import dev.ultima.meshing.FastPathCriteria.Result;

/**
 * Admission for the synthetic fixture palette ({@link SectionFixtures}). Mirrors the production
 * tree in {@link FastPathCriteria} for kernel and oracle tests only; production admits cells
 * through {@code CubeModelCache.lookup}.
 */
public final class FixtureAdmission {
    private FixtureAdmission() {
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
