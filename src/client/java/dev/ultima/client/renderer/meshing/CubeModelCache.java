package dev.ultima.client.renderer.meshing;

import dev.ultima.meshing.FastPathCriteria;
import dev.ultima.meshing.FastPathCriteria.Reason;
import dev.ultima.meshing.FastPathCriteria.Result;
import dev.ultima.meshing.VanillaWeightedPick;
import dev.ultima.mixin.mesher_fast_path.WeightedVariantsAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Worker-local cache of vanilla baked unit-cube quads, keyed by
 * {@code BlockState} identity so facing/axis/lit variants of one block are
 * distinct entries. A miss means fallback. Geometry in a hit is the vanilla
 * {@code BakedQuad} itself.
 *
 * <p>Weighted unit cubes (stone, dirt, deepslate, sand, …) store <em>every</em>
 * alternative cube and pick with {@link VanillaWeightedPick} using
 * {@code BlockState.getSeed(pos)}, matching vanilla
 * {@code WeightedVariants.collectParts}.
 */
public final class CubeModelCache {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float EPS = 1.0e-4F;

    public record CachedCube(BakedQuad[] quads, boolean useAmbientOcclusion) {
        public BakedQuad quad(final Direction direction) {
            return this.quads[direction.ordinal()];
        }
    }

    public record Lookup(@Nullable CachedCube cube, Result result) {
        public boolean fastPath() {
            return this.cube != null && this.result.fastPath();
        }
    }

    private sealed interface CachedGeometry {
        CachedCube pick(long seed, RandomSource random);

        Result result();
    }

    private record SimpleCube(CachedCube cube, Result result) implements CachedGeometry {
        @Override
        public CachedCube pick(final long seed, final RandomSource random) {
            return this.cube;
        }
    }

    private record WeightedCubes(
            WeightedList<BlockStateModel> list,
            IdentityHashMap<BlockStateModel, CachedCube> cubes,
            Result result) implements CachedGeometry {
        @Override
        public CachedCube pick(final long seed, final RandomSource random) {
            BlockStateModel chosen = VanillaWeightedPick.pick(this.list, random, seed);
            CachedCube cube = this.cubes.get(chosen);
            if (cube == null) {
                throw new IllegalStateException("weighted cube pick missed a baked child");
            }
            return cube;
        }
    }

    private final IdentityHashMap<BlockState, CachedGeometry> hits = new IdentityHashMap<>();
    private final IdentityHashMap<BlockState, Reason> misses = new IdentityHashMap<>();
    private final List<BlockStateModelPart> parts = new ArrayList<>(4);
    private final RandomSource random = VanillaWeightedPick.mesherRandom();
    private Object boundModelSet;

    /**
     * Bind this cache to the current {@code BlockStateModelSet} (or any identity
     * marker for the active model/atlas generation). A new identity is a
     * resource reload: drop every cached quad so stale UVs cannot survive
     * F3+T / resource-pack / mipmap changes on a long-lived worker thread.
     */
    public void bindModelSet(final Object modelSet) {
        if (this.boundModelSet != modelSet) {
            this.clear();
            this.boundModelSet = modelSet;
        }
    }

    public void clear() {
        this.hits.clear();
        this.misses.clear();
    }

    public boolean isEmpty() {
        return this.hits.isEmpty() && this.misses.isEmpty();
    }

    public int cachedEntryCount() {
        return this.hits.size() + this.misses.size();
    }

    public Object boundModelSet() {
        return this.boundModelSet;
    }

    public @Nullable CachedCube get(
            final BlockState state,
            final BlockStateModel model,
            final boolean cutoutLeaves,
            final long seed) {
        return this.lookup(state, model, cutoutLeaves, seed).cube();
    }

    /**
     * Same admission tree as {@link FastPathCriteria}. A miss reason is the
     * coverage bucket; a hit is a proven opaque unit cube for this
     * {@code BlockState}, possibly picked from a weighted family using
     * {@code seed}.
     */
    public Lookup lookup(
            final BlockState state,
            final BlockStateModel model,
            final boolean cutoutLeaves,
            final long seed) {
        Result early = classifyState(state, cutoutLeaves);
        if (!early.fastPath()) {
            this.misses.put(state, early.reason());
            return new Lookup(null, early);
        }
        CachedGeometry hit = this.hits.get(state);
        if (hit != null) {
            return new Lookup(hit.pick(seed, this.random), hit.result());
        }
        Reason remembered = this.misses.get(state);
        if (remembered != null) {
            return new Lookup(null, Result.fallback(remembered));
        }
        Inspected inspected = inspect(model);
        if (inspected.geometry() == null) {
            this.misses.put(state, inspected.result().reason());
            return new Lookup(null, inspected.result());
        }
        this.hits.put(state, inspected.geometry());
        return new Lookup(inspected.geometry().pick(seed, this.random), inspected.result());
    }

    static Result classifyState(final BlockState state, final boolean cutoutLeaves) {
        if (state.isAir()) {
            return Result.fallback(Reason.AIR);
        }
        if (!state.getFluidState().isEmpty()) {
            return Result.fallback(Reason.HAS_FLUID);
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return Result.fallback(Reason.NOT_MODEL);
        }
        if (state.hasOffsetFunction()) {
            return Result.fallback(Reason.HAS_OFFSET);
        }
        if (state.getBlock() instanceof LeavesBlock || ModelBlockRenderer.forceOpaque(cutoutLeaves, state)) {
            return Result.fallback(Reason.SKIP_RENDERING);
        }
        return Result.admitted();
    }

    private record Inspected(@Nullable CachedGeometry geometry, Result result) {
    }

    private Inspected inspect(final BlockStateModel model) {
        if (model instanceof MultiPartModel) {
            return new Inspected(null, Result.fallback(Reason.MULTIPART_MODEL));
        }
        if (model instanceof WeightedVariants weighted) {
            return inspectWeighted(weighted);
        }
        if (!(model instanceof SingleVariant)) {
            return new Inspected(null, Result.fallback(Reason.NOT_SINGLE_VARIANT));
        }
        Lookup simple = inspectSimple(model);
        if (simple.cube() == null) {
            return new Inspected(null, simple.result());
        }
        return new Inspected(new SimpleCube(simple.cube(), simple.result()), simple.result());
    }

    private Inspected inspectWeighted(final WeightedVariants weighted) {
        WeightedList<BlockStateModel> list = ((WeightedVariantsAccessor)(Object)weighted).ultima$list();
        List<Weighted<BlockStateModel>> entries = list.unwrap();
        if (entries.isEmpty()) {
            return new Inspected(null, Result.fallback(Reason.NOT_SINGLE_VARIANT));
        }
        IdentityHashMap<BlockStateModel, CachedCube> cubes = new IdentityHashMap<>();
        for (Weighted<BlockStateModel> entry : entries) {
            BlockStateModel child = entry.value();
            if (!(child instanceof SingleVariant)) {
                return new Inspected(null, Result.fallback(Reason.WEIGHTED_NON_CUBE));
            }
            Lookup childLookup = inspectSimple(child);
            if (childLookup.cube() == null) {
                return new Inspected(null, Result.fallback(Reason.WEIGHTED_NON_CUBE));
            }
            cubes.put(child, childLookup.cube());
        }
        Result admitted = Result.admittedWeighted();
        return new Inspected(new WeightedCubes(list, cubes, admitted), admitted);
    }

    private Lookup inspectSimple(final BlockStateModel model) {
        this.parts.clear();
        this.random.setSeed(0L);
        model.collectParts(this.random, this.parts);
        if (this.parts.size() != 1) {
            return new Lookup(null, Result.fallback(Reason.NOT_SINGLE_VARIANT));
        }
        BlockStateModelPart part = this.parts.getFirst();
        if (!part.getQuads(null).isEmpty()) {
            return new Lookup(null, Result.fallback(Reason.UNCULLED_QUADS));
        }
        BakedQuad[] quads = new BakedQuad[DIRECTIONS.length];
        for (Direction direction : DIRECTIONS) {
            List<BakedQuad> face = part.getQuads(direction);
            if (face.size() != 1) {
                return new Lookup(null, Result.fallback(Reason.FACE_QUAD_COUNT));
            }
            BakedQuad quad = face.getFirst();
            if (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) {
                return new Lookup(null, Result.fallback(Reason.TRANSLUCENT_LAYER));
            }
            if (quad.direction() != direction) {
                return new Lookup(null, Result.fallback(Reason.FACE_DIRECTION_MISMATCH));
            }
            if (!isUnitCubeFace(quad, direction)) {
                return new Lookup(null, Result.fallback(Reason.NOT_UNIT_CUBE_FACE));
            }
            quads[direction.ordinal()] = quad;
        }
        return new Lookup(new CachedCube(quads, part.useAmbientOcclusion()), Result.admitted());
    }

    static boolean isUnitCubeFace(final BakedQuad quad, final Direction direction) {
        float expected = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        Direction.Axis axis = direction.getAxis();
        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = quad.position(vertex);
            float onAxis = switch (axis) {
                case X -> position.x();
                case Y -> position.y();
                case Z -> position.z();
            };
            if (Math.abs(onAxis - expected) > EPS) {
                return false;
            }
            if (!unitCoord(position.x()) || !unitCoord(position.y()) || !unitCoord(position.z())) {
                return false;
            }
        }
        return true;
    }

    private static boolean unitCoord(final float value) {
        return Math.abs(value) <= EPS || Math.abs(value - 1.0F) <= EPS;
    }
}
