package dev.ultima.review;

import dev.ultima.entityquery.EntityQueryClassifier;
import dev.ultima.entityquery.EntityQueryEarlyOut;
import dev.ultima.entityquery.EntityQueryKind;
import dev.ultima.entityquery.EntitySectionCounters;
import dev.ultima.util.EntitySectionQueryRange;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Differential empty-only early-out checks against a vanilla-style section query model.
 */
public final class EntityQueryEarlyOutEquivalenceTest {
    private EntityQueryEarlyOutEquivalenceTest() {
    }

    public static void run() {
        testClassifierHierarchy();
        testRandomSectionConfigurations();
        testBoundaryEntityDoesNotFalseEarlyOut();
        testCountersUpdateOnSectionMoveBeforeQuery();
        testUnknownKindNeverEarlyOuts();
        testCollidableCounterIndependentOfItems();
        System.out.println("Entity query early-out equivalence checks passed.");
    }

    private static void testClassifierHierarchy() {
        assertKind(net.minecraft.world.entity.player.Player.class, EntityQueryKind.PLAYERS);
        assertKind(net.minecraft.world.entity.LivingEntity.class, EntityQueryKind.LIVING);
        assertKind(net.minecraft.world.entity.item.ItemEntity.class, EntityQueryKind.ITEMS);
        assertKind(net.minecraft.world.entity.Entity.class, EntityQueryKind.ANY);
        assertKind(String.class, EntityQueryKind.UNKNOWN);
    }

    private static void assertKind(final Class<?> type, final EntityQueryKind expected) {
        EntityQueryKind actual = EntityQueryClassifier.classifyClass(type);
        if (actual != expected) {
            throw new AssertionError(type.getName() + " classified as " + actual + " expected " + expected);
        }
    }

    private static void testRandomSectionConfigurations() {
        Random random = new Random(0x514541524C595FL);
        for (int trial = 0; trial < 400; trial++) {
            World world = World.random(random);
            AABB box = randomBox(random);
            for (EntityQueryKind kind : new EntityQueryKind[] {
                EntityQueryKind.ANY,
                EntityQueryKind.PLAYERS,
                EntityQueryKind.LIVING,
                EntityQueryKind.ITEMS,
                EntityQueryKind.COLLIDABLE
            }) {
                boolean vanillaEmpty = world.vanillaEmpty(box, kind);
                boolean earlyOut = EntityQueryEarlyOut.proveEmpty(world, box, kind);
                if (earlyOut && !vanillaEmpty) {
                    throw new AssertionError("false empty early-out kind=" + kind + " trial=" + trial);
                }
                if (vanillaEmpty && world.allAccessibleCountersZero(box, kind) && boxIsSmall(box) && !earlyOut) {
                    throw new AssertionError("missed a proven-empty query kind=" + kind + " trial=" + trial);
                }
            }
        }
    }

    private static void testBoundaryEntityDoesNotFalseEarlyOut() {
        World world = new World();
        // Query cube [0,1)^3. Entity AABB sits on the max face (x=1) and still intersects
        // vanilla AABB.intersects because maxX > other.minX / minX < other.maxX.
        AABB query = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        AABB entityBox = new AABB(0.95, 0.25, 0.25, 1.15, 0.75, 0.75);
        world.add(SectionPos.asLong(0, 0, 0), Counted.PLAYER, entityBox);
        if (world.vanillaEmpty(query, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("vanilla must see the boundary player");
        }
        if (EntityQueryEarlyOut.proveEmpty(world, query, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("boundary player must not produce an empty early-out");
        }
        if (!EntityQueryEarlyOut.proveEmpty(world, query, EntityQueryKind.ITEMS)) {
            throw new AssertionError("item query with only a player in-range must early-out");
        }
    }

    private static void testCountersUpdateOnSectionMoveBeforeQuery() {
        World world = new World();
        long from = SectionPos.asLong(0, 0, 0);
        long to = SectionPos.asLong(1, 0, 0);
        AABB entityBoxFrom = new AABB(4.0, 4.0, 4.0, 4.6, 5.8, 4.6);
        AABB entityBoxTo = new AABB(20.0, 4.0, 4.0, 20.6, 5.8, 4.6);
        world.add(from, Counted.PLAYER, entityBoxFrom);
        AABB local = new AABB(4.0, 4.0, 4.0, 8.0, 8.0, 8.0);
        if (EntityQueryEarlyOut.proveEmpty(world, local, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("player still in the from-section");
        }
        world.move(from, to, Counted.PLAYER, entityBoxFrom, entityBoxTo);
        if (!EntityQueryEarlyOut.proveEmpty(world, local, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("after the move the from-section must prove empty before a query");
        }
        AABB dest = new AABB(20.0, 4.0, 4.0, 24.0, 8.0, 8.0);
        if (EntityQueryEarlyOut.proveEmpty(world, dest, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("destination section must have the player counter before the query");
        }
        if (world.get(from).counters().players() != 0) {
            throw new AssertionError("from-section player counter must be 0 after the move");
        }
        if (world.get(to).counters().players() != 1) {
            throw new AssertionError("to-section player counter must be 1 after the move");
        }
    }

    private static void testUnknownKindNeverEarlyOuts() {
        World world = new World();
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        if (EntityQueryEarlyOut.proveEmpty(world, box, EntityQueryKind.UNKNOWN)) {
            throw new AssertionError("UNKNOWN must fail open to vanilla");
        }
    }

    private static void testCollidableCounterIndependentOfItems() {
        World world = new World();
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        world.add(SectionPos.asLong(0, 0, 0), Counted.ITEM, new AABB(0.2, 0.2, 0.2, 0.4, 0.4, 0.4));
        if (EntityQueryEarlyOut.proveEmpty(world, box, EntityQueryKind.ITEMS)) {
            throw new AssertionError("item section must not early-out an item query");
        }
        if (!EntityQueryEarlyOut.proveEmpty(world, box, EntityQueryKind.COLLIDABLE)) {
            throw new AssertionError("items are not collidable; collidable early-out must fire");
        }
        if (!EntityQueryEarlyOut.proveEmpty(world, box, EntityQueryKind.PLAYERS)) {
            throw new AssertionError("item-only section must early-out a player query");
        }
        if (EntityQueryEarlyOut.proveEmpty(world, box, EntityQueryKind.ANY)) {
            throw new AssertionError("ANY must not skip a non-empty section");
        }
    }

    private static AABB randomBox(final Random random) {
        double minX = random.nextInt(-4, 5) * 4.0 + random.nextDouble();
        double minY = random.nextInt(-2, 3) * 4.0 + random.nextDouble();
        double minZ = random.nextInt(-4, 5) * 4.0 + random.nextDouble();
        return new AABB(
                minX,
                minY,
                minZ,
                minX + 0.5 + random.nextDouble() * 8.0,
                minY + 0.5 + random.nextDouble() * 8.0,
                minZ + 0.5 + random.nextDouble() * 8.0);
    }

    private static boolean boxIsSmall(final AABB box) {
        EntitySectionQueryRange range = EntitySectionQueryRange.ofVanillaChonkyExpansion(box);
        return range.packable() && range.volume() <= EntitySectionQueryRange.DIRECT_LOOKUP_BUDGET;
    }

    enum Counted {
        PLAYER(true, true, true, false),
        LIVING(false, true, true, false),
        ITEM(false, false, false, true),
        OTHER_COLLIDABLE(false, false, true, false),
        NEVER_COLLIDABLE(false, false, false, false);

        final EntitySectionCounters.CountedEntityKind kind;

        Counted(final boolean player, final boolean living, final boolean collidable, final boolean item) {
            this.kind = new EntitySectionCounters.CountedEntityKind(player, living, collidable, item);
        }
    }

    private static final class World implements EntityQueryEarlyOut.SectionView {
        private final Map<Long, Cell> cells = new HashMap<>();

        static World random(final Random random) {
            World world = new World();
            int n = random.nextInt(0, 12);
            for (int i = 0; i < n; i++) {
                long key = SectionPos.asLong(random.nextInt(-2, 3), random.nextInt(-2, 3), random.nextInt(-2, 3));
                Counted type = Counted.values()[random.nextInt(Counted.values().length)];
                int sx = SectionPos.x(key) * 16;
                int sy = SectionPos.y(key) * 16;
                int sz = SectionPos.z(key) * 16;
                AABB box = new AABB(sx + 1, sy + 1, sz + 1, sx + 2, sy + 3, sz + 2);
                world.add(key, type, box);
                if (random.nextBoolean() && world.cells.get(key) != null) {
                    world.cells.get(key).accessible = random.nextBoolean() || random.nextBoolean();
                }
            }
            return world;
        }

        void add(final long key, final Counted type, final AABB box) {
            Cell cell = this.cells.computeIfAbsent(key, k -> new Cell());
            cell.counters.add(type.kind);
            cell.empty = false;
            cell.occupants.add(new Occupant(type, box));
        }

        void move(final long from, final long to, final Counted type, final AABB fromBox, final AABB toBox) {
            Cell src = this.cells.get(from);
            if (src == null) {
                throw new AssertionError("missing source section");
            }
            src.counters.remove(type.kind);
            src.occupants.removeIf(o -> o.type == type && o.box.equals(fromBox));
            src.empty = src.occupants.isEmpty();
            this.add(to, type, toBox);
        }

        boolean vanillaEmpty(final AABB query, final EntityQueryKind kind) {
            EntitySectionQueryRange range = EntitySectionQueryRange.ofVanillaChonkyExpansion(query);
            if (range.inverted()) {
                return true;
            }
            boolean[] found = {false};
            range.forEachKey(key -> {
                Cell cell = this.cells.get(key);
                if (cell == null || cell.empty || !cell.accessible) {
                    return true;
                }
                for (Occupant occupant : cell.occupants) {
                    if (matches(occupant.type, kind) && occupant.box.intersects(query)) {
                        found[0] = true;
                        return false;
                    }
                }
                return true;
            });
            return !found[0];
        }

        boolean allAccessibleCountersZero(final AABB query, final EntityQueryKind kind) {
            EntitySectionQueryRange range = EntitySectionQueryRange.ofVanillaChonkyExpansion(query);
            boolean[] any = {false};
            range.forEachKey(key -> {
                Cell cell = this.cells.get(key);
                if (cell == null || cell.empty || !cell.accessible) {
                    return true;
                }
                if (kind == EntityQueryKind.ANY || cell.counters.has(kind)) {
                    any[0] = true;
                    return false;
                }
                return true;
            });
            return !any[0];
        }

        @Override
        public EntityQueryEarlyOut.View get(final long sectionKey) {
            Cell cell = this.cells.get(sectionKey);
            if (cell == null) {
                return null;
            }
            return new EntityQueryEarlyOut.View(cell.empty, cell.accessible, cell.counters);
        }

        private static boolean matches(final Counted type, final EntityQueryKind kind) {
            return switch (kind) {
                case ANY -> true;
                case PLAYERS -> type.kind.player();
                case LIVING -> type.kind.living();
                case ITEMS -> type.kind.item();
                case COLLIDABLE -> type.kind.collidable();
                case UNKNOWN -> true;
            };
        }
    }

    private static final class Cell {
        final EntitySectionCounters counters = new EntitySectionCounters();
        final java.util.ArrayList<Occupant> occupants = new java.util.ArrayList<>();
        boolean empty = true;
        boolean accessible = true;
    }

    private record Occupant(Counted type, AABB box) {
    }
}
