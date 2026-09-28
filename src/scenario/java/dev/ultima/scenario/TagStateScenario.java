package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Records tag membership and the cached block-state properties of every state in the block
 * registry, reloads the data packs, and records them again. Covers tag bitsets and the
 * block-state property cache, including the tag rebind after a reload.
 */
final class TagStateScenario implements Scenario {
    private static final int SETTLE_TICKS = 100;

    private final String name;
    private final int originX;
    private final int originZ;
    private boolean before;

    TagStateScenario(final String name, final int originX, final int originZ) {
        this.name = name;
        this.originX = originX;
        this.originZ = originZ;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public List<ChunkPos> chunks() {
        return List.of(new ChunkPos(Math.floorDiv(this.originX, 16), Math.floorDiv(this.originZ, 16)));
    }

    @Override
    public void setup(final Run run) {
        // Property queries that need a world use the (air) position at the arena origin.
    }

    @Override
    public boolean tick(final Run run, final int tick) {
        if (!this.before) {
            this.pass(run, "before");
            this.before = true;
            run.requestReloadOnce();
            return false;
        }
        if (!run.reloadSettled(SETTLE_TICKS)) {
            return false;
        }
        this.pass(run, "after");
        return true;
    }

    private void pass(final Run run, final String phase) {
        ServerLevel level = run.level();
        BlockPos pos = new BlockPos(this.originX, 100, this.originZ);
        List<TagKey<Block>> tags = List.of(
                BlockTags.LOGS, BlockTags.LEAVES, BlockTags.WALLS, BlockTags.STAIRS, BlockTags.SLABS,
                BlockTags.FENCES, BlockTags.PLANKS, BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_PICKAXE,
                BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE);
        for (Block block : BuiltInRegistries.BLOCK) {
            StringBuilder line = new StringBuilder(phase).append(' ')
                    .append(BuiltInRegistries.BLOCK.getKey(block)).append(':');
            List<BlockState> states = new ArrayList<>(block.getStateDefinition().getPossibleStates());
            for (BlockState state : states) {
                int mask = 0;
                for (int i = 0; i < tags.size(); i++) {
                    if (state.is(tags.get(i))) {
                        mask |= 1 << i;
                    }
                }
                line.append(' ').append(Integer.toHexString(mask))
                        .append(state.isSignalSource() ? 'S' : '-')
                        .append(state.hasAnalogOutputSignal() ? 'A' : '-')
                        .append(state.isPathfindable(PathComputationType.LAND) ? 'L' : '-')
                        .append(state.isPathfindable(PathComputationType.WATER) ? 'W' : '-')
                        .append(state.isPathfindable(PathComputationType.AIR) ? 'F' : '-')
                        .append(state.isRedstoneConductor(level, pos) ? 'R' : '-');
            }
            run.digest().add(this.name, line.toString());
        }
    }
}
