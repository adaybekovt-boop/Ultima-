package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * Scatters motionless items through a mostly empty column and records what box queries return,
 * including boxes that span empty sections and unloaded chunks. Covers the entity lookup and
 * empty-query modules.
 */
final class EntityQueryScenario implements Scenario {
    private static final int SIZE = 64;
    private static final int ITEMS = 320;
    private static final int QUERIES = 900;
    private static final int WAIT_TICKS = 4;

    private final String name;
    private final int originX;
    private final int originZ;

    EntityQueryScenario(final String name, final int originX, final int originZ) {
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
        for (int cx = Math.floorDiv(this.originX, 16); cx <= Math.floorDiv(this.originX + SIZE - 1, 16); cx++) {
            for (int cz = Math.floorDiv(this.originZ, 16); cz <= Math.floorDiv(this.originZ + SIZE - 1, 16); cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    @Override
    public void setup(final Run run) {
        ServerLevel level = run.level();
        Random random = new Random(4242);
        for (int i = 0; i < ITEMS; i++) {
            double x = this.originX + random.nextDouble() * SIZE;
            double z = this.originZ + random.nextDouble() * SIZE;
            double y = random.nextInt(4) == 0 ? -58 + random.nextDouble() * 6 : -30 + random.nextDouble() * 260;
            ItemEntity item = new ItemEntity(level, x, y, z, new ItemStack(Items.COBBLESTONE), 0.0, 0.0, 0.0);
            item.setNoGravity(true);
            level.addFreshEntity(item);
        }
    }

    @Override
    public boolean tick(final Run run, final int tick) {
        if (tick < WAIT_TICKS) {
            return false;
        }
        ServerLevel level = run.level();
        Random random = new Random(777);
        for (int q = 0; q < QUERIES; q++) {
            double cx = this.originX - 40 + random.nextDouble() * (SIZE + 80);
            double cz = this.originZ - 40 + random.nextDouble() * (SIZE + 80);
            double cy = -64 + random.nextDouble() * 384;
            double hx = 0.3 + random.nextDouble() * (q % 7 == 0 ? 60 : 6);
            double hy = 0.3 + random.nextDouble() * (q % 5 == 0 ? 300 : 12);
            double hz = 0.3 + random.nextDouble() * (q % 7 == 0 ? 60 : 6);
            AABB box = new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
            List<Entity> all = level.getEntities((Entity) null, box);
            run.digest().add(this.name, q + " items=" + items.size() + " all=" + all.size() + " " + positions(items));
        }
        return true;
    }

    private static String positions(final List<ItemEntity> items) {
        List<String> text = new ArrayList<>(items.size());
        for (ItemEntity item : items) {
            text.add(Long.toHexString(Double.doubleToRawLongBits(item.getX())) + "/"
                    + Long.toHexString(Double.doubleToRawLongBits(item.getY())) + "/"
                    + Long.toHexString(Double.doubleToRawLongBits(item.getZ())));
        }
        text.sort(null);
        return String.join(";", text);
    }
}
