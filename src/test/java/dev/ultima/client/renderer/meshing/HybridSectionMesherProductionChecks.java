package dev.ultima.client.renderer.meshing;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Behavioral checks of {@link HybridSectionMesher#forEachVisibleFastCubeFace}, which is the
 * culling loop {@code tessellateFastCube} actually runs. Not a grep of the source file.
 */
public final class HybridSectionMesherProductionChecks {
    private HybridSectionMesherProductionChecks() {
    }

    public static void assertCullingMatchesVanillaShouldRenderFace() {
        if (!tryBootstrap()) {
            throw new AssertionError(
                    "Minecraft bootstrap is required to call production Block.shouldRenderFace; "
                            + "this check must not pass by grepping HybridSectionMesher.java");
        }
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos origin = new BlockPos(8, 64, 8);
        MapBlockGetter world = new MapBlockGetter();
        world.set(origin, stone);
        for (Direction direction : Direction.values()) {
            world.set(origin.relative(direction), air);
        }

        List<Direction> isolated = visibleFaces(stone, world, origin, true, true);
        if (isolated.size() != Direction.values().length) {
            throw new AssertionError("isolated stone must emit all six faces, got " + isolated);
        }

        world.set(origin.relative(Direction.EAST), stone);
        List<Direction> againstStone = visibleFaces(stone, world, origin, true, true);
        if (againstStone.contains(Direction.EAST)) {
            throw new AssertionError("stone against stone must cull EAST, matching Block.shouldRenderFace, got "
                    + againstStone);
        }
        if (!againstStone.contains(Direction.WEST)) {
            throw new AssertionError("air neighbor WEST must stay visible");
        }

        EnumSet<Direction> vanilla = EnumSet.noneOf(Direction.class);
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighbor.setWithOffset(origin, direction);
            if (Block.shouldRenderFace(stone, world.getBlockState(neighbor), direction)) {
                vanilla.add(direction);
            }
        }
        if (!EnumSet.copyOf(againstStone).equals(vanilla)) {
            throw new AssertionError("production walker " + againstStone + " != vanilla shouldRenderFace " + vanilla);
        }

        List<Boolean> ao = new ArrayList<>();
        HybridSectionMesher.forEachVisibleFastCubeFace(
                stone, world, origin, neighbor, true, true, (direction, useAo) -> ao.add(useAo));
        if (ao.isEmpty() || ao.stream().anyMatch(flag -> !flag)) {
            throw new AssertionError("stone with AO enabled and zero light emission must request AO");
        }
        ao.clear();
        HybridSectionMesher.forEachVisibleFastCubeFace(
                stone, world, origin, neighbor, false, true, (direction, useAo) -> ao.add(useAo));
        if (ao.stream().anyMatch(flag -> flag)) {
            throw new AssertionError("section AO off must not request AO on the production walker");
        }
    }

    private static List<Direction> visibleFaces(
            final BlockState state,
            final BlockGetter view,
            final BlockPos pos,
            final boolean ambientOcclusion,
            final boolean cubeAo) {
        List<Direction> faces = new ArrayList<>();
        HybridSectionMesher.forEachVisibleFastCubeFace(
                state, view, pos, new BlockPos.MutableBlockPos(), ambientOcclusion, cubeAo, (direction, useAo) -> faces.add(direction));
        return faces;
    }

    private static boolean tryBootstrap() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            return Blocks.STONE != null && Blocks.AIR != null;
        } catch (Throwable error) {
            System.out.println("HybridSectionMesher production culling bootstrap failed: " + error);
            return false;
        }
    }

    private static final class MapBlockGetter implements BlockGetter {
        private final Map<BlockPos, BlockState> cells = new HashMap<>();

        void set(final BlockPos pos, final BlockState state) {
            this.cells.put(pos.immutable(), state);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos) {
            BlockState state = this.cells.get(pos);
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
