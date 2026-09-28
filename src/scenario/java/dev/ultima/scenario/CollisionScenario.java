package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Drives boxes of many sizes through a field of random collision shapes with {@code Entity.move}
 * and records every resulting position bit for bit. Covers the collision, supporting-block and
 * cursor-step modules, negative coordinates and chunk borders.
 */
final class CollisionScenario implements Scenario {
    private static final int SIZE = 40;
    private static final int FLOOR_Y = -61;
    private static final int CHAINS = 900;
    private static final int MOVES_PER_CHAIN = 8;
    /** Set without neighbour reactions so the field keeps exactly the states chosen here. */
    private static final int QUIET_FLAGS = 2 | 16;

    private static final String[] SHAPES = {
        "STONE", "OAK_STAIRS", "COBBLESTONE_STAIRS", "OAK_SLAB", "OAK_FENCE", "COBBLESTONE_WALL", "SNOW",
        "CACTUS", "COBWEB", "LADDER", "OAK_TRAPDOOR", "CAULDRON", "POWDER_SNOW", "HOPPER", "CHEST",
        "ENDER_CHEST", "ANVIL", "SCAFFOLDING", "GLASS_PANE", "IRON_BARS", "SLIME_BLOCK", "HONEY_BLOCK",
        "SOUL_SAND", "OAK_FENCE_GATE", "SNOW_BLOCK", "IRON_BLOCK", "DISPENSER", "RAIL", "POWERED_RAIL",
        "BOOKSHELF", "OAK_DOOR", "BAMBOO", "END_ROD", "COBBLESTONE", "SAND"
    };
    private static final double[] WIDTHS = {0.25, 0.4, 0.6, 0.9, 0.98, 1.4, 2.0};
    private static final double[] HEIGHTS = {0.25, 0.7, 0.9, 1.8, 1.95, 2.9};

    private final String name;
    private final int originX;
    private final int originZ;
    private final long seed;
    private boolean done;

    CollisionScenario(final String name, final int originX, final int originZ, final long seed) {
        this.name = name;
        this.originX = originX;
        this.originZ = originZ;
        this.seed = seed;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public List<ChunkPos> chunks() {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.floorDiv(this.originX - 2, 16); cx <= Math.floorDiv(this.originX + SIZE + 2, 16); cx++) {
            for (int cz = Math.floorDiv(this.originZ - 2, 16); cz <= Math.floorDiv(this.originZ + SIZE + 2, 16); cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    @Override
    public void setup(final Run run) {
        ServerLevel level = run.level();
        Random random = new Random(this.seed);
        List<BlockState> palette = Palette.states(SHAPES);
        run.digest().add(this.name, "palette-states " + palette.size());
        int floor = FLOOR_Y;
        for (int dx = -1; dx <= SIZE; dx++) {
            for (int dz = -1; dz <= SIZE; dz++) {
                for (int dy = 0; dy <= 8; dy++) {
                    BlockPos pos = new BlockPos(this.originX + dx, floor + 1 + dy, this.originZ + dz);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET_FLAGS);
                }
                level.setBlock(new BlockPos(this.originX + dx, floor, this.originZ + dz),
                        Blocks.STONE.defaultBlockState(), QUIET_FLAGS);
            }
        }
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dy = 0; dy < 3; dy++) {
                    double density = dy == 0 ? 0.30 : 0.09;
                    if (!palette.isEmpty() && random.nextDouble() < density) {
                        BlockState state = palette.get(random.nextInt(palette.size()));
                        level.setBlock(new BlockPos(this.originX + dx, floor + 1 + dy, this.originZ + dz),
                                state, QUIET_FLAGS);
                    }
                }
            }
        }
        this.driveBoxes(run, random, floor + 1);
        this.done = true;
    }

    private void driveBoxes(final Run run, final Random random, final int baseY) {
        ServerLevel level = run.level();
        for (int chain = 0; chain < CHAINS; chain++) {
            double width = WIDTHS[random.nextInt(WIDTHS.length)];
            double height = HEIGHTS[random.nextInt(HEIGHTS.length)];
            double x = this.originX + 1 + random.nextDouble() * (SIZE - 2);
            double y = baseY + random.nextDouble() * 3.5;
            double z = this.originZ + 1 + random.nextDouble() * (SIZE - 2);
            ItemEntity box = new ItemEntity(level, x, y, z, new ItemStack(Items.DIRT), 0.0, 0.0, 0.0);
            box.setBoundingBox(new AABB(x - width / 2, y, z - width / 2, x + width / 2, y + height, z + width / 2));
            for (int move = 0; move < MOVES_PER_CHAIN; move++) {
                Vec3 delta = new Vec3(step(random), vertical(random), step(random));
                box.move(MoverType.SELF, delta);
                run.digest().add(this.name, describe(chain, move, box));
            }
        }
    }

    private static double step(final Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> 0.0;
            case 1 -> (random.nextDouble() - 0.5) * 0.004;
            case 2 -> (random.nextDouble() - 0.5) * 0.4;
            case 3 -> (random.nextDouble() - 0.5) * 1.6;
            case 4 -> (random.nextDouble() - 0.5) * 4.0;
            default -> random.nextBoolean() ? 1.0 : -1.0;
        };
    }

    private static double vertical(final Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> -0.0784;
            case 1 -> 0.42;
            case 2 -> (random.nextDouble() - 0.5) * 3.0;
            case 3 -> -(random.nextDouble() * 4.0);
            default -> 0.0;
        };
    }

    private static String describe(final int chain, final int move, final ItemEntity box) {
        AABB bounds = box.getBoundingBox();
        return chain + ":" + move
                + " pos=" + bits(box.getX()) + "," + bits(box.getY()) + "," + bits(box.getZ())
                + " box=" + bits(bounds.minX) + "," + bits(bounds.minY) + "," + bits(bounds.minZ)
                + "," + bits(bounds.maxX) + "," + bits(bounds.maxY) + "," + bits(bounds.maxZ)
                + " ground=" + box.onGround()
                + " hcol=" + box.horizontalCollision
                + " vcol=" + box.verticalCollision
                + " on=" + box.getOnPos().asLong();
    }

    private static String bits(final double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    @Override
    public boolean tick(final Run run, final int tick) {
        return this.done;
    }
}
