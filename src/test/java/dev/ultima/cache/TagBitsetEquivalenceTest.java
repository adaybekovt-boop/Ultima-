package dev.ultima.cache;

import dev.ultima.cache.tags.TagBitsetIndex;
import dev.ultima.cache.tags.TagBitsetRuntime;
import dev.ultima.config.UltimaModules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Bitset construction, reload invalidation, fallback, 200-trial differential, and an isolated
 * microbenchmark. The microbenchmark is not an FPS claim.
 */
public final class TagBitsetEquivalenceTest {
    private static final int TRIALS = 200;
    private static final ResourceKey<? extends Registry<Block>> BLOCKS = Registries.BLOCK;

    private TagBitsetEquivalenceTest() {
    }

    public static void run() {
        testEmptyTagIsFalseNotMiss();
        testUnknownTagFallsBack();
        testOutOfRangeIdFallsBack();
        testRegistryMismatchFallsBackViaRuntime();
        testReloadRebuildDoesNotLeakOldMembership();
        testDropClearsSnapshot();
        testModuleDefaultOffAndLithium();
        testDifferentialRandomUniverses();
        testLiveBuiltInRegistryIdsIfAvailable();
        runIsolatedMembershipMicrobench();
        System.out.println("Tag bitset equivalence checks passed.");
    }

    private static void testEmptyTagIsFalseNotMiss() {
        TagKey<Block> empty = tag("empty");
        TagBitsetIndex index = TagBitsetIndex.builder(1).registry(BLOCKS, 16).emptyTag(empty).build();
        assertEquals(Boolean.FALSE, index.contains(empty, 0), "empty tag must be a hit-false");
        assertEquals(Boolean.FALSE, index.contains(empty, 15), "last id of empty tag is false");
    }

    private static void testUnknownTagFallsBack() {
        TagKey<Block> known = tag("known");
        TagKey<Block> unknown = tag("unknown");
        TagBitsetIndex index = TagBitsetIndex.builder(1).registry(BLOCKS, 8).member(known, 3).build();
        assertEquals(Boolean.TRUE, index.contains(known, 3), "member is true");
        assertEquals(Boolean.FALSE, index.contains(known, 4), "non-member of known tag is false");
        assertTrue(index.contains(unknown, 3) == null, "unknown tag must miss for vanilla fallback");
    }

    private static void testOutOfRangeIdFallsBack() {
        TagKey<Block> wool = tag("wool");
        TagBitsetIndex index = TagBitsetIndex.builder(1).registry(BLOCKS, 4).member(wool, 1).build();
        assertTrue(index.contains(wool, -1) == null, "negative id falls back");
        assertTrue(index.contains(wool, 4) == null, "id == bitLength falls back");
        assertTrue(index.contains(wool, 99) == null, "huge id falls back");
        assertEquals(Boolean.TRUE, index.contains(wool, 1), "in-range member");
    }

    private static void testRegistryMismatchFallsBackViaRuntime() {
        TagKey<Block> wool = tag("wool");
        TagBitsetIndex index = TagBitsetIndex.builder(7).registry(BLOCKS, 4).member(wool, 2).build();
        TagKey<?> itemTag = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("ultima", "wool"));
        assertTrue(index.contains(itemTag, 2) == null, "item tag must not read the block bitset");
        TagBitsetRuntime.replaceSnapshotForTest(index);
        try {
            Boolean mismatch = TagBitsetRuntime.lookup(Registries.ITEM, wool, 2);
            assertTrue(mismatch == null, "item holder vs block tag (or module-off) is a vanilla fallback");
        } finally {
            TagBitsetRuntime.replaceSnapshotForTest(null);
        }
    }

    private static void testReloadRebuildDoesNotLeakOldMembership() {
        TagKey<Block> wool = tag("reload_wool");
        TagBitsetIndex before = TagBitsetIndex.builder(1).registry(BLOCKS, 8).member(wool, 0).member(wool, 7).build();
        assertEquals(Boolean.TRUE, before.contains(wool, 0), "generation 1 has id 0");
        TagBitsetIndex after = TagBitsetIndex.builder(2).registry(BLOCKS, 8).member(wool, 3).build();
        assertEquals(Boolean.FALSE, after.contains(wool, 0), "generation 2 dropped id 0");
        assertEquals(Boolean.TRUE, after.contains(wool, 3), "generation 2 added id 3");
        assertTrue(after.generation() != before.generation(), "reload bumps generation");
        TagBitsetRuntime.replaceSnapshotForTest(before);
        TagBitsetRuntime.drop("simulated_reload_start");
        assertTrue(TagBitsetRuntime.snapshot() == null, "drop must publish null before rebuild");
        TagBitsetRuntime.replaceSnapshotForTest(after);
        assertEquals(after, TagBitsetRuntime.snapshot(), "rebuild publishes the new snapshot");
        TagBitsetRuntime.replaceSnapshotForTest(null);
    }

    private static void testDropClearsSnapshot() {
        TagBitsetIndex index = TagBitsetIndex.builder(3).registry(BLOCKS, 2).emptyTag(tag("x")).build();
        TagBitsetRuntime.replaceSnapshotForTest(index);
        TagBitsetRuntime.drop("test");
        assertTrue(TagBitsetRuntime.snapshot() == null, "drop clears the live snapshot");
        assertTrue(
                TagBitsetRuntime.lookup(BLOCKS, tag("x"), 0) == null,
                "lookup after drop is a vanilla fallback");
    }

    private static void testModuleDefaultOffAndLithium() {
        UltimaModules.Module module = UltimaModules.byKey("tag_bitsets");
        assertTrue(module != null, "tag_bitsets is registered");
        assertTrue(!module.enabledByDefault(), "tag_bitsets default off");
        assertTrue(module.incompatibleMods().contains("lithium"), "Lithium overlap");
        assertTrue(module.incompatibleMods().contains("canary"), "Canary is a Lithium fork");
        assertTrue(module.incompatibleMods().contains("radium"), "Radium is a Lithium fork");
        assertTrue(!module.clientOnly(), "tag bitsets are common/server-safe");
    }

    private static void testDifferentialRandomUniverses() {
        Random random = new Random(0x544147534554L);
        int probes = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            int size = 16 + random.nextInt(192);
            int tagCount = 1 + random.nextInt(12);
            TagBitsetIndex.Builder builder = TagBitsetIndex.builder(trial + 10);
            builder.registry(BLOCKS, size);
            Map<TagKey<Block>, Set<Integer>> truth = new HashMap<>();
            ArrayList<TagKey<Block>> tags = new ArrayList<>();
            for (int t = 0; t < tagCount; t++) {
                TagKey<Block> created = tag("t" + trial + "_" + t);
                tags.add(created);
                Set<Integer> members = new HashSet<>();
                builder.emptyTag(created);
                int membership = random.nextInt(size + 1);
                for (int m = 0; m < membership; m++) {
                    int id = random.nextInt(size);
                    builder.member(created, id);
                    members.add(id);
                }
                truth.put(created, members);
            }
            TagBitsetIndex index = builder.build();
            TagKey<Block> uncovered = tag("uncovered_" + trial);
            for (int q = 0; q < 64; q++) {
                TagKey<Block> tag = tags.get(random.nextInt(tagCount));
                int id = random.nextInt(size);
                boolean expected = truth.get(tag).contains(id);
                Boolean actual = index.contains(tag, id);
                if (actual == null || actual.booleanValue() != expected) {
                    throw new AssertionError(
                            "trial " + trial + " tag=" + tag + " id=" + id + " expected=" + expected + " actual=" + actual);
                }
                probes++;
            }
            if (index.contains(uncovered, random.nextInt(size)) != null) {
                throw new AssertionError("uncovered tag must miss in trial " + trial);
            }
            if (index.contains(tags.get(0), size) != null) {
                throw new AssertionError("out-of-range id must miss in trial " + trial);
            }
        }
        if (probes != TRIALS * 64) {
            throw new AssertionError("probe count " + probes);
        }
        System.out.println("Tag bitset differential: " + TRIALS + " universes, " + probes + " probes, 0 mismatches.");
    }

    private static void testLiveBuiltInRegistryIdsIfAvailable() {
        Registry<Block> registry;
        try {
            registry = BuiltInRegistries.BLOCK;
            if (registry.size() < 16) {
                System.out.println("Skipping live-registry bitset check: BLOCK registry too small.");
                return;
            }
        } catch (Throwable t) {
            System.out.println("Skipping live-registry bitset check: " + t);
            return;
        }

        int bitLength = 0;
        for (Block block : registry) {
            bitLength = Math.max(bitLength, registry.getId(block) + 1);
        }
        TagKey<Block> everyThird = tag("live_every_third");
        TagBitsetIndex.Builder builder = TagBitsetIndex.builder(99).registry(BLOCKS, bitLength).emptyTag(everyThird);
        Set<Integer> truth = new HashSet<>();
        int examined = 0;
        for (Block block : registry) {
            int id = registry.getId(block);
            if (id < 0) {
                continue;
            }
            examined++;
            if (id % 3 == 0) {
                builder.member(everyThird, id);
                truth.add(id);
            }
        }
        TagBitsetIndex index = builder.build();
        int mismatches = 0;
        for (Block block : registry) {
            int id = registry.getId(block);
            if (id < 0) {
                continue;
            }
            boolean expected = truth.contains(id);
            Boolean actual = index.contains(everyThird, id);
            if (actual == null || actual.booleanValue() != expected) {
                mismatches++;
            }
        }
        if (mismatches != 0) {
            throw new AssertionError("live registry mismatches=" + mismatches + " examined=" + examined);
        }
        System.out.println("Tag bitset live BLOCK registry: examined=" + examined + " bitLength=" + bitLength + " members=" + truth.size());
    }

    private static void runIsolatedMembershipMicrobench() {
        final int size = 4096;
        TagKey<Block> tag = tag("bench");
        Set<Integer> members = new HashSet<>();
        TagBitsetIndex.Builder builder = TagBitsetIndex.builder(1).registry(BLOCKS, size).emptyTag(tag);
        for (int id = 0; id < size; id += 2) {
            builder.member(tag, id);
            members.add(id);
        }
        TagBitsetIndex index = builder.build();
        int[] ids = new int[size];
        for (int i = 0; i < size; i++) {
            ids[i] = i;
        }
        Boolean sink = Boolean.FALSE;
        for (int i = 0; i < 10_000; i++) {
            sink = index.contains(tag, ids[i & (size - 1)]);
            sink = members.contains(ids[i & (size - 1)]);
        }
        final int iterations = 400_000;
        long bitsetNs = 0L;
        long setNs = 0L;
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink = index.contains(tag, ids[i & (size - 1)]);
        }
        bitsetNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink = members.contains(ids[i & (size - 1)]);
        }
        setNs = System.nanoTime() - t0;
        if (sink == null) {
            throw new AssertionError("bench sink");
        }
        double bitsetPer = bitsetNs / (double)iterations;
        double setPer = setNs / (double)iterations;
        System.out.printf(
                "Isolated membership microbench (NOT an FPS claim): bitset=%.2f ns/op HashSet=%.2f ns/op ratio=%.2fx%n",
                bitsetPer,
                setPer,
                setPer / Math.max(0.001, bitsetPer));
    }

    private static TagKey<Block> tag(final String path) {
        return TagKey.create(BLOCKS, Identifier.fromNamespaceAndPath("ultima", path));
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
