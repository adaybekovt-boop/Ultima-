package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The steady loads the benchmark measures. Each is built from fixed seeds and positions. */
final class BenchWorkloads {
    private static final int Y = -60;
    private static final int PLACE_FLAGS = 3;

    private BenchWorkloads() {
    }

    static Map<String, BenchWorkload> all() {
        return Map.of(
                "farm", new Farm(),
                "hoppers", new Hoppers(),
                "collision", new CollisionArena(),
                "furnaces", new Furnaces());
    }

    private static List<ChunkPos> square(final int minBlockX, final int minBlockZ, final int size) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.floorDiv(minBlockX, 16); cx <= Math.floorDiv(minBlockX + size - 1, 16); cx++) {
            for (int cz = Math.floorDiv(minBlockZ, 16); cz <= Math.floorDiv(minBlockZ + size - 1, 16); cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    private static void command(final MinecraftServer server, final String text) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), text);
    }

    private static void clear(final ServerLevel level, final int x0, final int z0, final int size, final int height) {
        for (int x = x0; x < x0 + size; x++) {
            for (int z = z0; z < z0 + size; z++) {
                for (int dy = 0; dy < height; dy++) {
                    level.setBlock(new BlockPos(x, Y + dy, z), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                }
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
            }
        }
    }

    /** Roughly 800 wandering mobs and loose items spread over 12 x 12 chunks. */
    private static final class Farm implements BenchWorkload {
        private static final int SPAN = 96;
        private static final int MOBS = 500;
        private static final int ITEMS = 300;
        private static final String[] KINDS = {"zombie", "skeleton", "cow", "sheep", "pig", "chicken", "creeper", "spider"};

        @Override
        public String name() {
            return "farm";
        }

        @Override
        public List<ChunkPos> chunks() {
            return square(-SPAN, -SPAN, SPAN * 2);
        }

        @Override
        public void build(final Run run) {
            MinecraftServer server = run.server();
            command(server, "gamerule send_command_feedback false");
            command(server, "difficulty normal");
            long seed = 20260616L;
            for (int i = 0; i < MOBS + ITEMS; i++) {
                seed = (seed * 1103515245L + 12345L) % 2147483648L;
                int x = (int) (seed % (SPAN * 2)) - SPAN;
                seed = (seed * 1103515245L + 12345L) % 2147483648L;
                int z = (int) (seed % (SPAN * 2)) - SPAN;
                if (i < MOBS) {
                    command(server, "summon minecraft:" + KINDS[i % KINDS.length] + " " + x + ".5 -59.0 " + z + ".5");
                } else {
                    command(server, "summon minecraft:item " + x + ".5 -59.0 " + z + ".5 "
                            + "{Item:{id:\"minecraft:cobblestone\",count:1}}");
                }
            }
        }
    }

    /** Twelve rings of hoppers that keep items circulating, plus 600 idle hoppers. */
    private static final class Hoppers implements BenchWorkload {
        private static final int ORIGIN_X = 200;
        private static final int ORIGIN_Z = 200;

        @Override
        public String name() {
            return "hoppers";
        }

        @Override
        public List<ChunkPos> chunks() {
            return square(ORIGIN_X - 2, ORIGIN_Z - 2, 224);
        }

        @Override
        public void build(final Run run) {
            ServerLevel level = run.level();
            clear(level, ORIGIN_X - 1, ORIGIN_Z - 1, 222, 4);
            Item cobble = Palette.item("COBBLESTONE");
            for (int ring = 0; ring < 12; ring++) {
                this.buildRing(level, ORIGIN_X + ring * 16, ORIGIN_Z, cobble);
            }
            for (int line = 0; line < 40; line++) {
                int z = ORIGIN_Z + 20 + line * 2;
                for (int i = 0; i < 15; i++) {
                    level.setBlock(new BlockPos(ORIGIN_X + i, Y, z),
                            Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST), PLACE_FLAGS);
                }
                level.setBlock(new BlockPos(ORIGIN_X + 15, Y, z), Blocks.CHEST.defaultBlockState(), PLACE_FLAGS);
            }
        }

        private void buildRing(final ServerLevel level, final int x0, final int z0, final Item item) {
            List<BlockPos> path = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                path.add(new BlockPos(x0 + i, Y, z0));
            }
            for (int i = 0; i < 9; i++) {
                path.add(new BlockPos(x0 + 9, Y, z0 + i));
            }
            for (int i = 0; i < 9; i++) {
                path.add(new BlockPos(x0 + 9 - i, Y, z0 + 9));
            }
            for (int i = 0; i < 9; i++) {
                path.add(new BlockPos(x0, Y, z0 + 9 - i));
            }
            for (int i = 0; i < path.size(); i++) {
                BlockPos here = path.get(i);
                BlockPos next = path.get((i + 1) % path.size());
                Direction facing = next.getX() > here.getX() ? Direction.EAST
                        : next.getX() < here.getX() ? Direction.WEST
                        : next.getZ() > here.getZ() ? Direction.SOUTH : Direction.NORTH;
                level.setBlock(here, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing), PLACE_FLAGS);
            }
            if (level.getBlockEntity(path.get(0)) instanceof Container container) {
                for (int slot = 0; slot < 4; slot++) {
                    container.setItem(slot, new ItemStack(item, 32));
                }
            }
        }
    }

    /** About 200 mobs walking in a walled field of stairs, slabs, fences and walls. */
    private static final class CollisionArena implements BenchWorkload {
        private static final int ORIGIN_X = -400;
        private static final int ORIGIN_Z = -400;
        private static final int SIZE = 64;

        @Override
        public String name() {
            return "collision";
        }

        @Override
        public List<ChunkPos> chunks() {
            return square(ORIGIN_X - 2, ORIGIN_Z - 2, SIZE + 4);
        }

        @Override
        public void build(final Run run) {
            ServerLevel level = run.level();
            clear(level, ORIGIN_X - 1, ORIGIN_Z - 1, SIZE + 2, 6);
            List<BlockState> palette = Palette.states("OAK_STAIRS", "COBBLESTONE_STAIRS", "OAK_SLAB", "OAK_FENCE",
                    "COBBLESTONE_WALL", "SNOW", "OAK_TRAPDOOR", "CHEST", "ANVIL", "IRON_BARS");
            Random random = new Random(99);
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (!palette.isEmpty() && random.nextDouble() < 0.22) {
                        level.setBlock(new BlockPos(ORIGIN_X + x, Y, ORIGIN_Z + z),
                                palette.get(random.nextInt(palette.size())), PLACE_FLAGS);
                    }
                }
            }
            for (int i = -1; i <= SIZE; i++) {
                for (int dy = 0; dy < 4; dy++) {
                    level.setBlock(new BlockPos(ORIGIN_X + i, Y + dy, ORIGIN_Z - 1), Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
                    level.setBlock(new BlockPos(ORIGIN_X + i, Y + dy, ORIGIN_Z + SIZE), Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
                    level.setBlock(new BlockPos(ORIGIN_X - 1, Y + dy, ORIGIN_Z + i), Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
                    level.setBlock(new BlockPos(ORIGIN_X + SIZE, Y + dy, ORIGIN_Z + i), Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
                }
            }
            MinecraftServer server = run.server();
            command(server, "gamerule send_command_feedback false");
            for (int i = 0; i < 200; i++) {
                int x = ORIGIN_X + 1 + random.nextInt(SIZE - 2);
                int z = ORIGIN_Z + 1 + random.nextInt(SIZE - 2);
                command(server, "summon minecraft:" + (i % 2 == 0 ? "zombie" : "cow") + " " + x + ".5 " + (Y + 2) + ".0 " + z + ".5");
            }
        }
    }

    /** 160 furnaces, each loaded with enough coal and raw iron to smelt for the whole run. */
    private static final class Furnaces implements BenchWorkload {
        private static final int ORIGIN_X = 500;
        private static final int ORIGIN_Z = -300;

        @Override
        public String name() {
            return "furnaces";
        }

        @Override
        public List<ChunkPos> chunks() {
            return square(ORIGIN_X - 1, ORIGIN_Z - 1, 40);
        }

        @Override
        public void build(final Run run) {
            ServerLevel level = run.level();
            clear(level, ORIGIN_X - 1, ORIGIN_Z - 1, 40, 4);
            Item coal = Palette.item("COAL");
            Item rawIron = Palette.item("RAW_IRON");
            for (int i = 0; i < 160; i++) {
                BlockPos pos = new BlockPos(ORIGIN_X + (i % 16) * 2, Y, ORIGIN_Z + (i / 16) * 3);
                level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), PLACE_FLAGS);
                if (level.getBlockEntity(pos) instanceof Container container) {
                    container.setItem(0, new ItemStack(rawIron, 64));
                    container.setItem(1, new ItemStack(coal, 32));
                }
            }
        }
    }
}
