package dev.ultima.client.renderer.meshing;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Worker-local cache of vanilla baked unit-cube quads. A miss means fallback.
 * Geometry in a hit is the vanilla {@code BakedQuad} itself.
 */
public final class CubeModelCache {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float EPS = 1.0e-4F;

    public record CachedCube(BakedQuad[] quads, boolean useAmbientOcclusion) {
        public BakedQuad quad(final Direction direction) {
            return this.quads[direction.ordinal()];
        }
    }

    private final IdentityHashMap<BlockState, CachedCube> hits = new IdentityHashMap<>();
    private final IdentityHashMap<BlockState, Boolean> misses = new IdentityHashMap<>();
    private final List<BlockStateModelPart> parts = new ArrayList<>(4);
    private final RandomSource random = RandomSource.create(0L);
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

    public @Nullable CachedCube get(final BlockState state, final BlockStateModel model, final boolean cutoutLeaves) {
        if (state.getBlock() instanceof LeavesBlock || ModelBlockRenderer.forceOpaque(cutoutLeaves, state)) {
            this.misses.put(state, Boolean.TRUE);
            return null;
        }
        CachedCube hit = this.hits.get(state);
        if (hit != null) {
            return hit;
        }
        if (this.misses.containsKey(state)) {
            return null;
        }
        CachedCube inspected = inspect(state, model);
        if (inspected == null) {
            this.misses.put(state, Boolean.TRUE);
            return null;
        }
        this.hits.put(state, inspected);
        return inspected;
    }

    private @Nullable CachedCube inspect(final BlockState state, final BlockStateModel model) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return null;
        }
        if (!state.getFluidState().isEmpty()) {
            return null;
        }
        if (state.hasOffsetFunction()) {
            return null;
        }
        if (state.getBlock() instanceof LeavesBlock) {
            return null;
        }
        if (!(model instanceof SingleVariant)) {
            return null;
        }
        this.parts.clear();
        this.random.setSeed(0L);
        model.collectParts(this.random, this.parts);
        if (this.parts.size() != 1) {
            return null;
        }
        BlockStateModelPart part = this.parts.getFirst();
        if (!part.getQuads(null).isEmpty()) {
            return null;
        }
        BakedQuad[] quads = new BakedQuad[DIRECTIONS.length];
        for (Direction direction : DIRECTIONS) {
            List<BakedQuad> face = part.getQuads(direction);
            if (face.size() != 1) {
                return null;
            }
            BakedQuad quad = face.getFirst();
            if (quad.direction() != direction || !isUnitCubeFace(quad, direction)) {
                return null;
            }
            quads[direction.ordinal()] = quad;
        }
        return new CachedCube(quads, part.useAmbientOcclusion());
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
