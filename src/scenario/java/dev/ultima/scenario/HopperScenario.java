package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Builds hopper rigs (vertical chain, horizontal chain, item pickup, furnace feed and drain,
 * partially filled stacks) and lets them run, then records every container slot. Covers hopper
 * sleeping, container slot masks and the furnace recipe lookup.
 */
final class HopperScenario implements Scenario {
    private static final int RUN_TICKS = 280;
    private static final int Y = -60;
    private static final int QUIET_FLAGS = 3;

    private final String name;
    private final int originX;
    private final int originZ;
    private final List<BlockPos> containers = new ArrayList<>();

    HopperScenario(final String name, final int originX, final int originZ) {
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
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.floorDiv(this.originX - 1, 16); cx <= Math.floorDiv(this.originX + 31, 16); cx++) {
            for (int cz = Math.floorDiv(this.originZ - 1, 16); cz <= Math.floorDiv(this.originZ + 31, 16); cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    private BlockPos at(final int dx, final int dy, final int dz) {
        return new BlockPos(this.originX + dx, Y + dy, this.originZ + dz);
    }

    private static BlockState hopper(final Direction facing) {
        return Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing);
    }

    private void place(final ServerLevel level, final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, QUIET_FLAGS);
    }

    private void container(final ServerLevel level, final BlockPos pos, final Block block) {
        this.place(level, pos, block.defaultBlockState());
        this.containers.add(pos);
    }

    private static void fill(final ServerLevel level, final BlockPos pos, final ItemStack... stacks) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int i = 0; i < stacks.length && i < container.getContainerSize(); i++) {
                container.setItem(i, stacks[i]);
            }
        }
    }

    @Override
    public void setup(final Run run) {
        ServerLevel level = run.level();
        for (int dx = -1; dx <= 30; dx++) {
            for (int dz = -1; dz <= 30; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    this.place(level, this.at(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
                this.place(level, this.at(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        Item cobble = Palette.item("COBBLESTONE");
        Item dirt = Palette.item("DIRT");
        Item coal = Palette.item("COAL");
        Item rawIron = Palette.item("RAW_IRON");
        Item sand = Palette.item("SAND");
        Item stick = Palette.item("STICK");

        // Rig 1: chest -> hopper -> chest, straight down.
        this.container(level, this.at(2, 3, 2), Blocks.CHEST);
        this.place(level, this.at(2, 2, 2), hopper(Direction.DOWN));
        this.container(level, this.at(2, 1, 2), Blocks.CHEST);
        fill(level, this.at(2, 3, 2), new ItemStack(cobble, 64), new ItemStack(dirt, 33), new ItemStack(sand, 7),
                new ItemStack(stick, 64), new ItemStack(cobble, 5));

        // Rig 2: five hoppers in a row feeding a dropper.
        this.container(level, this.at(6, 1, 6), Blocks.CHEST);
        for (int i = 0; i < 5; i++) {
            this.place(level, this.at(7 + i, 1, 6), hopper(Direction.EAST));
        }
        this.container(level, this.at(12, 1, 6), Blocks.DROPPER);
        fill(level, this.at(6, 1, 6), new ItemStack(dirt, 20), new ItemStack(sand, 20), new ItemStack(cobble, 20));

        // Rig 3: a hopper collecting loose items lying above it, emptying into a chest below.
        this.place(level, this.at(16, 2, 2), hopper(Direction.DOWN));
        this.container(level, this.at(16, 1, 2), Blocks.CHEST);
        for (int i = 0; i < 6; i++) {
            ItemEntity loose = new ItemEntity(level, this.originX + 16.5, Y + 3.2 + i * 0.05, this.originZ + 2.5,
                    new ItemStack(i % 2 == 0 ? cobble : sand, 3 + i), 0.0, 0.0, 0.0);
            loose.setNoGravity(true);
            level.addFreshEntity(loose);
        }

        // Rig 4: coal and raw iron pushed into a furnace, the result drained into a chest.
        this.container(level, this.at(20, 4, 10), Blocks.CHEST);
        this.place(level, this.at(20, 3, 10), hopper(Direction.DOWN));
        this.container(level, this.at(20, 2, 10), Blocks.FURNACE);
        this.place(level, this.at(20, 1, 10), hopper(Direction.DOWN));
        this.container(level, this.at(20, 0, 10), Blocks.CHEST);
        fill(level, this.at(20, 4, 10), new ItemStack(rawIron, 3), new ItemStack(coal, 2));

        // Rig 5: a hopper feeding sideways into a chest that is nearly full of partial stacks.
        this.container(level, this.at(10, 1, 14), Blocks.CHEST);
        this.place(level, this.at(11, 1, 14), hopper(Direction.WEST));
        this.container(level, this.at(12, 1, 14), Blocks.CHEST);
        ItemStack[] partial = new ItemStack[27];
        for (int i = 0; i < partial.length; i++) {
            partial[i] = new ItemStack(i % 3 == 0 ? cobble : i % 3 == 1 ? dirt : sand, 40 + i % 24);
        }
        fill(level, this.at(12, 1, 14), partial);
        fill(level, this.at(10, 1, 14), new ItemStack(cobble, 64), new ItemStack(dirt, 64), new ItemStack(sand, 64));
    }

    @Override
    public boolean tick(final Run run, final int tick) {
        if (tick < RUN_TICKS) {
            return false;
        }
        ServerLevel level = run.level();
        for (BlockPos pos : this.containers) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof Container container) {
                run.digest().add(this.name, describe(pos, container));
            } else {
                run.digest().add(this.name, offset(pos) + " no-container");
            }
        }
        AABB area = new AABB(this.originX - 1, Y - 2, this.originZ - 1, this.originX + 31, Y + 8, this.originZ + 31);
        List<String> loose = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
            loose.add(itemName(item.getItem()) + "x" + item.getItem().getCount()
                    + "@" + Long.toHexString(Double.doubleToRawLongBits(item.getY())));
        }
        loose.sort(null);
        run.digest().add(this.name, "loose " + String.join(",", loose));
        return true;
    }

    private String offset(final BlockPos pos) {
        return (pos.getX() - this.originX) + "," + (pos.getY() - Y) + "," + (pos.getZ() - this.originZ);
    }

    private String describe(final BlockPos pos, final Container container) {
        StringBuilder text = new StringBuilder(this.offset(pos)).append(':');
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                text.append(' ').append(slot).append('=').append(itemName(stack)).append('x').append(stack.getCount());
            }
        }
        return text.toString();
    }

    private static String itemName(final ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
