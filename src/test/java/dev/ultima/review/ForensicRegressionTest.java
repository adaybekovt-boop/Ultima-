package dev.ultima.review;

import dev.ultima.config.UltimaConfig;
import dev.ultima.util.SectionRangeMath;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import net.minecraft.core.SectionPos;

/**
 * Dependency-free differential checks for arithmetic used by the optimization Mixins.
 */
public final class ForensicRegressionTest {
    private ForensicRegressionTest() {
    }

    public static void main(final String[] args) {
        testSaturatedSectionVolume();
        testPackedSectionBounds();
        testEntitySectionOrder();
        testCursorCarry();
        testInteriorCursorAndIndex();
        testConfigParsingAndDependencies();
        System.out.println("Forensic regression checks passed.");
    }

    private static void testSaturatedSectionVolume() {
        assertEquals(1L, SectionRangeMath.saturatedVolume(0, 0, 0, 0, 0, 0), "unit volume");
        assertEquals(24L, SectionRangeMath.saturatedVolume(-1, -2, 4, 0, 0, 7), "mixed-sign volume");
        assertEquals(
                Long.MAX_VALUE,
                SectionRangeMath.saturatedVolume(
                        Integer.MIN_VALUE,
                        Integer.MIN_VALUE,
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE),
                "2^96 volume must saturate");
    }

    private static void testPackedSectionBounds() {
        assertTrue(
                SectionRangeMath.isPackable(
                        -(1 << 21), -(1 << 19), -(1 << 21), (1 << 21) - 1, (1 << 19) - 1, (1 << 21) - 1),
                "SectionPos boundary must be accepted");
        assertFalse(
                SectionRangeMath.isPackable(0, 1 << 19, 0, 0, 1 << 19, 0),
                "out-of-range y aliases a different packed section");
        assertFalse(
                SectionRangeMath.isPackable(1 << 21, 0, 0, 1 << 21, 0, 0),
                "out-of-range x aliases a different packed section");
    }

    private static void testEntitySectionOrder() {
        Random random = new Random(0x554C54494D41L);
        for (int trial = 0; trial < 10_000; trial++) {
            TreeSet<Long> populated = new TreeSet<>();
            int entries = random.nextInt(200);
            for (int i = 0; i < entries; i++) {
                populated.add(SectionPos.asLong(
                        random.nextInt(-8, 9),
                        random.nextInt(-16, 17),
                        random.nextInt(-16, 17)));
            }

            int xMin = random.nextInt(-8, 9);
            int xMax = random.nextInt(xMin, 9);
            int yMin = random.nextInt(-16, 17);
            int yMax = random.nextInt(yMin, 17);
            int zMin = random.nextInt(-16, 17);
            int zMax = random.nextInt(zMin, 17);
            long volume = SectionRangeMath.saturatedVolume(xMin, yMin, zMin, xMax, yMax, zMax);
            if (volume > 1024L && volume > populated.size()) {
                continue;
            }

            List<Long> vanilla = vanillaSectionOrder(populated, xMin, yMin, zMin, xMax, yMax, zMax);
            List<Long> direct = directSectionOrder(populated, xMin, yMin, zMin, xMax, yMax, zMax);
            if (!vanilla.equals(direct)) {
                throw new AssertionError("entity section order mismatch: vanilla=" + vanilla + ", direct=" + direct);
            }
        }
    }

    private static List<Long> vanillaSectionOrder(
            final TreeSet<Long> populated,
            final int xMin,
            final int yMin,
            final int zMin,
            final int xMax,
            final int yMax,
            final int zMax) {
        List<Long> result = new ArrayList<>();
        for (long xCursor = xMin; xCursor <= xMax; xCursor++) {
            int x = (int)xCursor;
            long from = SectionPos.asLong(x, 0, 0);
            long to = SectionPos.asLong(x, -1, -1) + 1L;
            for (long key : populated.subSet(from, true, to, false)) {
                int y = SectionPos.y(key);
                int z = SectionPos.z(key);
                if (y >= yMin && y <= yMax && z >= zMin && z <= zMax) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    private static List<Long> directSectionOrder(
            final TreeSet<Long> populated,
            final int xMin,
            final int yMin,
            final int zMin,
            final int xMax,
            final int yMax,
            final int zMax) {
        List<Long> result = new ArrayList<>();
        for (long xCursor = xMin; xCursor <= xMax; xCursor++) {
            int x = (int)xCursor;
            for (int zHalf = 0; zHalf < 2; zHalf++) {
                int zFrom = zHalf == 0 ? Math.max(zMin, 0) : zMin;
                int zTo = zHalf == 0 ? zMax : Math.min(zMax, -1);
                for (long zCursor = zFrom; zCursor <= zTo; zCursor++) {
                    int z = (int)zCursor;
                    for (int yHalf = 0; yHalf < 2; yHalf++) {
                        int yFrom = yHalf == 0 ? Math.max(yMin, 0) : yMin;
                        int yTo = yHalf == 0 ? yMax : Math.min(yMax, -1);
                        for (long yCursor = yFrom; yCursor <= yTo; yCursor++) {
                            long key = SectionPos.asLong(x, (int)yCursor, z);
                            if (populated.contains(key)) {
                                result.add(key);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void testCursorCarry() {
        Random random = new Random(0x435552534F52L);
        for (int trial = 0; trial < 100_000; trial++) {
            int width = random.nextInt(1, 20);
            int height = random.nextInt(1, 20);
            int depth = random.nextInt(1, 20);
            CursorModel vanilla = new CursorModel(width, height, depth);
            CursorModel carried = new CursorModel(width, height, depth);
            while (vanilla.advanceVanilla()) {
                assertTrue(carried.advanceCarried(), "carried cursor ended early");
                assertCursorEquals(vanilla, carried);
            }
            assertFalse(carried.advanceCarried(), "carried cursor emitted an extra position");
        }
    }

    private static void testInteriorCursorAndIndex() {
        for (int width = 1; width <= 12; width++) {
            for (int height = 1; height <= 12; height++) {
                for (int depth = 1; depth <= 12; depth++) {
                    CursorModel vanilla = new CursorModel(width, height, depth);
                    List<String> expected = new ArrayList<>();
                    while (vanilla.advanceVanilla()) {
                        if (vanilla.type() == 0) {
                            expected.add(vanilla.positionAndIndex());
                        }
                    }

                    CursorModel interior = new CursorModel(width, height, depth);
                    List<String> actual = new ArrayList<>();
                    while (interior.advanceInterior()) {
                        actual.add(interior.positionAndIndex());
                    }
                    if (!expected.equals(actual)) {
                        throw new AssertionError("interior cursor mismatch for " + width + "x" + height + "x" + depth);
                    }
                    assertEquals(interior.end, interior.index, "exhausted interior index");
                }
            }
        }
    }

    private static void testConfigParsingAndDependencies() {
        try {
            Method parseBoolean = UltimaConfig.class.getDeclaredMethod("parseBoolean", String.class);
            parseBoolean.setAccessible(true);
            assertTrue(Boolean.TRUE.equals(parseBoolean.invoke(null, " TRUE ")), "true config value");
            assertTrue(Boolean.FALSE.equals(parseBoolean.invoke(null, "false")), "false config value");
            assertTrue(parseBoolean.invoke(null, "enabled") == null, "invalid config value must not become false");

            Constructor<UltimaConfig> constructor = UltimaConfig.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            Map<String, Boolean> modules = new LinkedHashMap<>();
            modules.put("collision_shell_skip", true);
            modules.put("cursor_step", false);
            UltimaConfig config = constructor.newInstance(modules);
            assertFalse(config.isEnabled("collision_shell_skip"), "shell skip must require cursor step");
            assertEquals(0L, config.enabledModuleCount(), "dependency-aware enabled count");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not exercise config guards", e);
        }
    }

    private static void assertCursorEquals(final CursorModel expected, final CursorModel actual) {
        if (expected.x != actual.x || expected.y != actual.y || expected.z != actual.z
                || expected.index != actual.index || expected.type() != actual.type()) {
            throw new AssertionError("cursor mismatch: expected=" + expected.positionAndIndex()
                    + ", actual=" + actual.positionAndIndex());
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static final class CursorModel {
        private final int width;
        private final int height;
        private final int depth;
        private final int end;
        private int index;
        private int x;
        private int y;
        private int z;
        private boolean interiorStarted;
        private boolean interiorExhausted;

        private CursorModel(final int width, final int height, final int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.end = width * height * depth;
        }

        private boolean advanceVanilla() {
            if (this.index == this.end) {
                return false;
            }
            this.x = this.index % this.width;
            int slice = this.index / this.width;
            this.y = slice % this.height;
            this.z = slice / this.height;
            this.index++;
            return true;
        }

        private boolean advanceCarried() {
            if (this.index == this.end) {
                return false;
            }
            if (this.index != 0 && ++this.x == this.width) {
                this.x = 0;
                if (++this.y == this.height) {
                    this.y = 0;
                    this.z++;
                }
            }
            this.index++;
            return true;
        }

        private boolean advanceInterior() {
            if (this.interiorExhausted) {
                return false;
            }
            if (!this.interiorStarted) {
                if (this.width < 3 || this.height < 3 || this.depth < 3) {
                    this.interiorExhausted = true;
                    this.index = this.end;
                    return false;
                }
                this.interiorStarted = true;
                this.x = 1;
                this.y = 1;
                this.z = 1;
                this.index = this.width * this.height + this.width + 2;
                return true;
            }
            if (++this.x == this.width - 1) {
                this.x = 1;
                if (++this.y == this.height - 1) {
                    this.y = 1;
                    if (++this.z == this.depth - 1) {
                        this.interiorExhausted = true;
                        this.index = this.end;
                        return false;
                    }
                    this.index += 2 * this.width + 3;
                    return true;
                }
                this.index += 3;
                return true;
            }
            this.index++;
            return true;
        }

        private int type() {
            int type = 0;
            if (this.x == 0 || this.x == this.width - 1) {
                type++;
            }
            if (this.y == 0 || this.y == this.height - 1) {
                type++;
            }
            if (this.z == 0 || this.z == this.depth - 1) {
                type++;
            }
            return type;
        }

        private String positionAndIndex() {
            return this.x + "," + this.y + "," + this.z + "@" + this.index;
        }
    }
}
