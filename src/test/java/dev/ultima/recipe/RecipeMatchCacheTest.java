package dev.ultima.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Differential proof that the first-match table returns the same recipe, output, and remnants as a
 * 26.2-faithful vanilla scan reconstructed from {@code RecipeMap.getRecipesFor},
 * {@code ShapelessRecipe.matches}, {@code ShapedRecipePattern.matches},
 * {@code SingleItemRecipe.matches}, and {@code PotionBrewing.mix}.
 */
public final class RecipeMatchCacheTest {
    private static final long SEED = 0x554C54494D412D52L;
    private static final int RANDOM_CRAFTING_TRIALS = 220;
    private static final int RANDOM_FURNACE_TRIALS = 220;
    private static final int RANDOM_BREWING_TRIALS = 220;
    private static final int WATER_BUCKET = 20;
    private static final int BUCKET = 21;
    private static final int MAP_ITEM = 30;
    private static final int PAPER = 31;

    private RecipeMatchCacheTest() {
    }

    public static void main(final String[] args) {
        testVanillaClassPolicy();
        testShapelessOrderIndependence();
        testShapedGeometrySensitivity();
        testOverlappingFirstMatch();
        testCountIgnoredForShapelessAndCooking();
        testRepairCountMatters();
        testImpureFallback();
        testReloadInvalidation();
        testRandomCraftingGrids();
        testRandomFurnaceInputs();
        testRandomBrewingMixes();
        testMicroBenchmark();
        System.out.println("Recipe match-cache differential checks passed.");
    }

    private static void testVanillaClassPolicy() {
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("MapExtendingRecipe").getSuperclass().getName(),
                "MapExtendingRecipe is CustomRecipe (impure: reads Level map data)");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("RepairItemRecipe").getSuperclass().getName(),
                "RepairItemRecipe is CustomRecipe");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("FireworkRocketRecipe").getSuperclass().getName(),
                "FireworkRocketRecipe is CustomRecipe");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("BookCloningRecipe").getSuperclass().getName(),
                "BookCloningRecipe is CustomRecipe");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("ShieldDecorationRecipe").getSuperclass().getName(),
                "ShieldDecorationRecipe is CustomRecipe");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("BannerDuplicateRecipe").getSuperclass().getName(),
                "BannerDuplicateRecipe is CustomRecipe");
        assertEquals(
                "net.minecraft.world.item.crafting.CustomRecipe",
                recipeClass("DecoratedPotRecipe").getSuperclass().getName(),
                "DecoratedPotRecipe is CustomRecipe");
        assertTrue(
                recipeClass("ShapedRecipe").getSuperclass().getName().endsWith("NormalCraftingRecipe"),
                "ShapedRecipe is declarative");
        assertTrue(
                recipeClass("ShapelessRecipe").getSuperclass().getName().endsWith("NormalCraftingRecipe"),
                "ShapelessRecipe is declarative");
        assertTrue(
                recipeClass("TransmuteRecipe").getSuperclass().getName().endsWith("NormalCraftingRecipe"),
                "TransmuteRecipe is declarative");
        assertEquals(
                "net.minecraft.world.item.crafting.SingleItemRecipe",
                recipeClass("AbstractCookingRecipe").getSuperclass().getName(),
                "cooking recipes are single-item");
        assertEquals(
                "net.minecraft.world.item.crafting.AbstractCookingRecipe",
                recipeClass("SmeltingRecipe").getSuperclass().getName(),
                "smelting is a cooking recipe");
        assertEquals(
                "net.minecraft.world.item.crafting.AbstractCookingRecipe",
                recipeClass("BlastingRecipe").getSuperclass().getName(),
                "blasting is a cooking recipe");
        assertEquals(
                "net.minecraft.world.item.crafting.AbstractCookingRecipe",
                recipeClass("SmokingRecipe").getSuperclass().getName(),
                "smoking is a cooking recipe");
    }

    private static Class<?> recipeClass(final String simpleName) {
        try {
            return Class.forName("net.minecraft.world.item.crafting." + simpleName, false, RecipeMatchCacheTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("missing vanilla recipe class " + simpleName, e);
        }
    }

    private static void testShapelessOrderIndependence() {
        List<ModelRecipe> recipes = List.of(ModelRecipe.shapeless(1, List.of(1, 2), 50, 1));
        int[][] a = grid(item(1), item(2), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        int[][] b = grid(item(2), empty(), item(1), empty(), empty(), empty(), empty(), empty(), empty());
        MatchResult vanillaA = vanillaCraft(recipes, a, WorldState.DEFAULT);
        MatchResult vanillaB = vanillaCraft(recipes, b, WorldState.DEFAULT);
        assertEquals(vanillaA.recipeId, vanillaB.recipeId, "26.2 shapeless matcher does not depend on slot order");
        assertEquals(vanillaA.outputId, vanillaB.outputId, "shapeless output is order-independent");
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        assertEquals(vanillaA, cache.craft(a), "cache first shapeless placement");
        assertEquals(vanillaB, cache.craft(b), "cache second shapeless placement");
        assertEquals(vanillaA.recipeId, cache.craft(b).recipeId, "cache keeps the same shapeless recipe across permutations");
    }

    private static void testShapedGeometrySensitivity() {
        List<ModelRecipe> recipes = List.of(
                ModelRecipe.shaped(2, 2, 2, new int[] {1, 1, 1, 0}, 60, 1),
                ModelRecipe.shapeless(3, List.of(1, 1, 1), 61, 1));
        int[][] stairs = grid(item(1), item(1), empty(), item(1), empty(), empty(), empty(), empty(), empty());
        int[][] scattered = grid(item(1), empty(), item(1), empty(), item(1), empty(), empty(), empty(), empty());
        MatchResult vanillaStairs = vanillaCraft(recipes, stairs, WorldState.DEFAULT);
        MatchResult vanillaScattered = vanillaCraft(recipes, scattered, WorldState.DEFAULT);
        assertEquals(2, vanillaStairs.recipeId, "shaped stairs-like pattern matches the shaped recipe first");
        assertEquals(3, vanillaScattered.recipeId, "the same items in a non-pattern layout miss shaped and hit shapeless");
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        assertEquals(vanillaStairs, cache.craft(stairs), "cache preserves shaped geometry");
        assertEquals(vanillaScattered, cache.craft(scattered), "cache does not canonicalize shapeless over shaped");
        assertTrue(vanillaStairs.recipeId != vanillaScattered.recipeId, "geometry-sensitive and geometry-insensitive results differ");
    }

    private static void testOverlappingFirstMatch() {
        List<ModelRecipe> recipes = List.of(
                ModelRecipe.shapeless(4, List.of(5), 70, 1),
                ModelRecipe.shapeless(5, List.of(5), 71, 4));
        int[][] grid = grid(item(5), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        MatchResult vanilla = vanillaCraft(recipes, grid, WorldState.DEFAULT);
        assertEquals(4, vanilla.recipeId, "overlapping recipes keep list order, not 'any match'");
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        assertEquals(vanilla, cache.craft(grid), "first cache fill");
        assertEquals(vanilla, cache.craft(grid), "cache hit still returns the first list match");
    }

    private static void testCountIgnoredForShapelessAndCooking() {
        List<ModelRecipe> crafting = List.of(ModelRecipe.shapeless(6, List.of(7), 80, 4));
        int[][] one = grid(item(7, 0, 1), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        int[][] sixtyFour = grid(item(7, 0, 64), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        MatchResult vanillaOne = vanillaCraft(crafting, one, WorldState.DEFAULT);
        MatchResult vanillaMany = vanillaCraft(crafting, sixtyFour, WorldState.DEFAULT);
        assertEquals(vanillaOne.recipeId, vanillaMany.recipeId, "shapeless matching accounts occupancy, not stack size");
        List<ModelRecipe> cooking = List.of(ModelRecipe.cooking(8, 9, 90, 1));
        assertEquals(
                vanillaCook(cooking, slot(item(9, 0, 1))).recipeId,
                vanillaCook(cooking, slot(item(9, 0, 64))).recipeId,
                "furnace matching ignores count");
        CachedScanner cache = new CachedScanner(cooking, WorldState.DEFAULT);
        assertEquals(vanillaCook(cooking, slot(item(9, 0, 1))), cache.cook(slot(item(9, 0, 1))), "furnace cache count=1");
        assertEquals(vanillaCook(cooking, slot(item(9, 0, 64))), cache.cook(slot(item(9, 0, 64))), "furnace cache count=64");
        assertEquals(1, cache.furnaceTable.size(), "furnace key omits count so both sizes share an entry");
    }

    private static void testRepairCountMatters() {
        List<ModelRecipe> recipes = List.of(ModelRecipe.repair(10, 11));
        int[][] singles = grid(item(11, 0, 1), item(11, 0, 1), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        int[][] stacked = grid(item(11, 0, 2), item(11, 0, 1), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        MatchResult vanillaSingles = vanillaCraft(recipes, singles, WorldState.DEFAULT);
        MatchResult vanillaStacked = vanillaCraft(recipes, stacked, WorldState.DEFAULT);
        assertEquals(10, vanillaSingles.recipeId, "repair matches two count-1 tools");
        assertEquals(0, vanillaStacked.recipeId, "repair rejects a stacked tool, matching 26.2 canCombine");
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        assertEquals(vanillaSingles, cache.craft(singles), "repair cache count=1");
        assertEquals(vanillaStacked, cache.craft(stacked), "repair cache keeps count in the crafting key");
    }

    private static void testImpureFallback() {
        List<ModelRecipe> recipes = List.of(
                ModelRecipe.impureMap(12, 99),
                ModelRecipe.shapeless(13, List.of(MAP_ITEM, PAPER), 100, 1));
        int[][] mapGrid = grid(item(MAP_ITEM, 7, 1), item(PAPER), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        WorldState expandable = new WorldState(true);
        WorldState locked = new WorldState(false);
        MatchResult vanillaExpandable = vanillaCraft(recipes, mapGrid, expandable);
        MatchResult vanillaLocked = vanillaCraft(recipes, mapGrid, locked);
        assertEquals(12, vanillaExpandable.recipeId, "impure map recipe can match when world data allows it");
        assertEquals(13, vanillaLocked.recipeId, "the same grid selects the next recipe when world data forbids the map recipe");
        CachedScanner cache = new CachedScanner(recipes, expandable);
        assertEquals(vanillaExpandable, cache.craft(mapGrid), "first impure lookup bypasses the cache and scans");
        cache.world = locked;
        assertEquals(vanillaLocked, cache.craft(mapGrid), "impure inputs never return a stale cached first-match");
        assertEquals(0, cache.craftingTable.size(), "impure map grids are not stored");
        int[][] safe = grid(item(PAPER), item(PAPER), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        MatchResult vanillaSafe = vanillaCraft(recipes, safe, expandable);
        assertEquals(vanillaSafe, cache.craft(safe), "pure grids still cache while an impure recipe exists in the list");
        assertEquals(vanillaSafe, cache.craft(safe), "pure-grid cache hit");
        assertTrue(cache.craftingTable.size() > 0, "pure grids are stored");
    }

    private static void testReloadInvalidation() {
        List<ModelRecipe> before = List.of(ModelRecipe.shapeless(14, List.of(1), 110, 1));
        List<ModelRecipe> after = List.of(ModelRecipe.shapeless(15, List.of(1), 111, 8));
        int[][] grid = grid(item(1), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        CachedScanner cache = new CachedScanner(before, WorldState.DEFAULT);
        MatchResult first = cache.craft(grid);
        assertEquals(14, first.recipeId, "pre-reload first match");
        assertEquals(first, cache.craft(grid), "cached pre-reload");
        cache.reload(after);
        assertTrue(cache.craftingTable.isEmpty(), "/reload must clear the crafting table");
        assertTrue(cache.furnaceTable.isEmpty(), "/reload must clear the furnace table");
        assertTrue(cache.brewingTable.isEmpty(), "/reload must clear the brewing table");
        MatchResult reloaded = cache.craft(grid);
        assertEquals(vanillaCraft(after, grid, WorldState.DEFAULT), reloaded, "post-reload scan is stored fresh");
        assertEquals(15, reloaded.recipeId, "reload can change which recipe is first");
    }

    private static void testRandomCraftingGrids() {
        List<ModelRecipe> recipes = List.of(
                ModelRecipe.shaped(16, 3, 3, new int[] {1, 1, 1, 1, 0, 1, 1, 1, 1}, 120, 1),
                ModelRecipe.shaped(17, 1, 2, new int[] {2, 2}, 121, 4),
                ModelRecipe.shapeless(18, List.of(3, 4), 122, 1),
                ModelRecipe.shapeless(19, List.of(3), 123, 4),
                ModelRecipe.repair(20, 11),
                ModelRecipe.impureMap(21, 124));
        int[] palette = {0, 1, 2, 3, 4, 11, MAP_ITEM, PAPER, WATER_BUCKET};
        Random random = new Random(SEED);
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        int compared = 0;
        for (int trial = 0; trial < RANDOM_CRAFTING_TRIALS; trial++) {
            int[][] slots = randomGrid(random, palette);
            WorldState world = (trial & 1) == 0 ? WorldState.DEFAULT : new WorldState(false);
            cache.world = world;
            MatchResult vanilla = vanillaCraft(recipes, slots, world);
            MatchResult cached = cache.craft(slots);
            if (!vanilla.equals(cached)) {
                throw new AssertionError("crafting mismatch trial=" + trial + " vanilla=" + vanilla + " cache=" + cached);
            }
            MatchResult cachedAgain = cache.craft(slots);
            if (!vanilla.equals(cachedAgain)) {
                throw new AssertionError("crafting cache-hit mismatch trial=" + trial);
            }
            compared++;
        }
        assertTrue(compared == RANDOM_CRAFTING_TRIALS, "expected " + RANDOM_CRAFTING_TRIALS + " crafting trials");
        System.out.println("Crafting differential trials: " + compared + " PASS");
    }

    private static void testRandomFurnaceInputs() {
        List<ModelRecipe> recipes = List.of(
                ModelRecipe.cooking(22, 1, 200, 1),
                ModelRecipe.cooking(23, 2, 201, 1),
                ModelRecipe.cooking(24, 3, 202, 1));
        Random random = new Random(SEED ^ 0x4655524E);
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        int[] items = {0, 1, 2, 3, 4, 9};
        int compared = 0;
        for (int trial = 0; trial < RANDOM_FURNACE_TRIALS; trial++) {
            Slot input = slot(item(items[random.nextInt(items.length)], random.nextInt(3), 1 + random.nextInt(64)));
            MatchResult vanilla = vanillaCook(recipes, input);
            MatchResult cached = cache.cook(input);
            if (!vanilla.equals(cached)) {
                throw new AssertionError("furnace mismatch trial=" + trial + " vanilla=" + vanilla + " cache=" + cached);
            }
            if (!vanilla.equals(cache.cook(input))) {
                throw new AssertionError("furnace cache-hit mismatch trial=" + trial);
            }
            compared++;
        }
        assertTrue(compared == RANDOM_FURNACE_TRIALS, "expected " + RANDOM_FURNACE_TRIALS + " furnace trials");
        System.out.println("Furnace differential trials: " + compared + " PASS");
    }

    private static void testRandomBrewingMixes() {
        List<BrewMix> mixes = List.of(
                BrewMix.container(40, 41, 42),
                BrewMix.potion(40, 1, 50, 2),
                BrewMix.potion(40, 2, 51, 3),
                BrewMix.potion(43, 1, 52, 4));
        int[] containers = {0, 40, 43, 44};
        int[] ingredients = {0, 41, 50, 51, 52, 53};
        int[] potions = {0, 1, 2, 3};
        Random random = new Random(SEED ^ 0x42524557);
        CachedScanner cache = new CachedScanner(List.of(), WorldState.DEFAULT);
        cache.brewMixes = mixes;
        int compared = 0;
        for (int trial = 0; trial < RANDOM_BREWING_TRIALS; trial++) {
            Slot source = new Slot(containers[random.nextInt(containers.length)], potions[random.nextInt(potions.length)], 1);
            Slot ingredient = slot(item(ingredients[random.nextInt(ingredients.length)]));
            BrewResult vanilla = vanillaBrew(mixes, source, ingredient);
            BrewResult cached = cache.brew(source, ingredient);
            if (!vanilla.equals(cached)) {
                throw new AssertionError("brewing mismatch trial=" + trial + " vanilla=" + vanilla + " cache=" + cached);
            }
            if (!vanilla.equals(cache.brew(source, ingredient))) {
                throw new AssertionError("brewing cache-hit mismatch trial=" + trial);
            }
            compared++;
        }
        assertTrue(compared == RANDOM_BREWING_TRIALS, "expected " + RANDOM_BREWING_TRIALS + " brewing trials");
        System.out.println("Brewing differential trials: " + compared + " PASS");
    }

    private static void testMicroBenchmark() {
        List<ModelRecipe> recipes = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            recipes.add(ModelRecipe.shapeless(1000 + i, List.of(100 + (i % 17), 200 + (i % 9)), 300 + i, 1));
        }
        recipes.add(ModelRecipe.shapeless(2000, List.of(1, 2), 400, 1));
        int[][] hitGrid = grid(item(1), item(2), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        int[][] missGrid = grid(item(8), item(9), empty(), empty(), empty(), empty(), empty(), empty(), empty());
        CachedScanner cache = new CachedScanner(recipes, WorldState.DEFAULT);
        cache.craft(hitGrid);
        cache.craft(missGrid);
        int iterations = 8_000;
        long vanillaHit = time(() -> {
            for (int i = 0; i < iterations; i++) {
                vanillaCraft(recipes, hitGrid, WorldState.DEFAULT);
            }
        });
        long cachedHit = time(() -> {
            for (int i = 0; i < iterations; i++) {
                cache.craft(hitGrid);
            }
        });
        long vanillaMiss = time(() -> {
            for (int i = 0; i < iterations; i++) {
                vanillaCraft(recipes, missGrid, WorldState.DEFAULT);
            }
        });
        long cachedMiss = time(() -> {
            for (int i = 0; i < iterations; i++) {
                cache.craft(missGrid);
            }
        });
        System.out.println(
                "Lookup micro-benchmark (" + iterations + " iterations, 256-recipe list): hit vanilla="
                        + vanillaHit + "ns cache=" + cachedHit + "ns miss vanilla=" + vanillaMiss + "ns cache="
                        + cachedMiss + "ns");
        assertTrue(cachedHit > 0L && vanillaHit > 0L, "micro-benchmark produced timings");
    }

    private static long time(final Runnable action) {
        action.run();
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static MatchResult vanillaCraft(final List<ModelRecipe> recipes, final int[][] raw, final WorldState world) {
        CraftingGrid grid = CraftingGrid.shrink(raw);
        if (grid.isEmpty()) {
            return MatchResult.none(grid);
        }
        for (ModelRecipe recipe : recipes) {
            if (recipe.matchesCrafting(grid, world)) {
                return MatchResult.from(recipe, grid);
            }
        }
        return MatchResult.none(grid);
    }

    private static MatchResult vanillaCook(final List<ModelRecipe> recipes, final Slot input) {
        if (input.isEmpty()) {
            return MatchResult.noneFurnace(input);
        }
        for (ModelRecipe recipe : recipes) {
            if (recipe.matchesCooking(input)) {
                return MatchResult.fromCooking(recipe, input);
            }
        }
        return MatchResult.noneFurnace(input);
    }

    private static BrewResult vanillaBrew(final List<BrewMix> mixes, final Slot source, final Slot ingredient) {
        boolean isIngredient = false;
        if (!ingredient.isEmpty()) {
            for (BrewMix mix : mixes) {
                if (mix.ingredientId == ingredient.item) {
                    isIngredient = true;
                    break;
                }
            }
        }
        boolean hasMix = false;
        if (isContainer(source) && !ingredient.isEmpty()) {
            for (BrewMix mix : mixes) {
                if (mix.matches(source, ingredient)) {
                    hasMix = true;
                    break;
                }
            }
        }
        Slot mixed = source;
        if (!source.isEmpty() && source.component != 0 && !ingredient.isEmpty()) {
            for (BrewMix mix : mixes) {
                if (mix.matches(source, ingredient)) {
                    mixed = mix.apply(source);
                    break;
                }
            }
        }
        return new BrewResult(isIngredient, hasMix, mixed);
    }

    private static boolean isContainer(final Slot source) {
        return source.item == 40 || source.item == 43;
    }

    private static int[][] randomGrid(final Random random, final int[] palette) {
        int[][] grid = new int[9][3];
        for (int i = 0; i < 9; i++) {
            int item = palette[random.nextInt(palette.length)];
            if (item == 0) {
                grid[i] = empty();
            } else {
                grid[i] = item(item, random.nextInt(4), 1 + random.nextInt(8));
            }
        }
        return grid;
    }

    private static int[][] grid(final int[]... slots) {
        return slots;
    }

    private static int[] empty() {
        return item(0, 0, 0);
    }

    private static int[] item(final int id) {
        return item(id, 0, 1);
    }

    private static int[] item(final int id, final int component, final int count) {
        return new int[] {id, component, id == 0 ? 0 : count};
    }

    private static Slot slot(final int[] cell) {
        return new Slot(cell[0], cell[1], cell[2]);
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final int expected, final int actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private record WorldState(boolean mapExpandable) {
        static final WorldState DEFAULT = new WorldState(true);
    }

    private enum Kind {
        SHAPED,
        SHAPELESS,
        COOKING,
        REPAIR,
        IMPURE_MAP
    }

    private record ModelRecipe(
            int id,
            Kind kind,
            int width,
            int height,
            int[] pattern,
            List<Integer> shapeless,
            int matchItem,
            int outputId,
            int outputCount,
            boolean special,
            boolean impure) {
        static ModelRecipe shaped(final int id, final int width, final int height, final int[] pattern, final int outputId, final int outputCount) {
            return new ModelRecipe(id, Kind.SHAPED, width, height, pattern, List.of(), 0, outputId, outputCount, false, false);
        }

        static ModelRecipe shapeless(final int id, final List<Integer> ingredients, final int outputId, final int outputCount) {
            return new ModelRecipe(id, Kind.SHAPELESS, 0, 0, new int[0], ingredients, 0, outputId, outputCount, false, false);
        }

        static ModelRecipe cooking(final int id, final int input, final int outputId, final int outputCount) {
            return new ModelRecipe(id, Kind.COOKING, 0, 0, new int[0], List.of(), input, outputId, outputCount, false, false);
        }

        static ModelRecipe repair(final int id, final int tool) {
            return new ModelRecipe(id, Kind.REPAIR, 0, 0, new int[0], List.of(), tool, tool, 1, true, false);
        }

        static ModelRecipe impureMap(final int id, final int outputId) {
            return new ModelRecipe(id, Kind.IMPURE_MAP, 0, 0, new int[0], List.of(), MAP_ITEM, outputId, 1, true, true);
        }

        boolean matchesCrafting(final CraftingGrid grid, final WorldState world) {
            return switch (this.kind) {
                case SHAPED -> matchesShaped(grid);
                case SHAPELESS -> matchesShapeless(grid);
                case REPAIR -> matchesRepair(grid);
                case IMPURE_MAP -> matchesImpureMap(grid, world);
                case COOKING -> false;
            };
        }

        boolean matchesCooking(final Slot input) {
            return this.kind == Kind.COOKING && input.item == this.matchItem;
        }

        private boolean matchesShaped(final CraftingGrid grid) {
            int occupied = 0;
            for (int cell : this.pattern) {
                if (cell != 0) {
                    occupied++;
                }
            }
            if (grid.ingredientCount != occupied || grid.width != this.width || grid.height != this.height) {
                return false;
            }
            boolean symmetrical = isSymmetrical(this.width, this.height, this.pattern);
            if (!symmetrical && matchesShaped(grid, true)) {
                return true;
            }
            return matchesShaped(grid, false);
        }

        private boolean matchesShaped(final CraftingGrid grid, final boolean flip) {
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    int patternIndex = flip ? this.width - x - 1 + y * this.width : x + y * this.width;
                    int expected = this.pattern[patternIndex];
                    Slot actual = grid.get(x, y);
                    if (expected == 0) {
                        if (!actual.isEmpty()) {
                            return false;
                        }
                    } else if (actual.item != expected) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean matchesShapeless(final CraftingGrid grid) {
            if (grid.ingredientCount != this.shapeless.size()) {
                return false;
            }
            if (grid.size() == 1 && this.shapeless.size() == 1) {
                return grid.get(0, 0).item == this.shapeless.getFirst();
            }
            List<Integer> remaining = new ArrayList<>(this.shapeless);
            for (Slot slot : grid.slots) {
                if (slot.isEmpty()) {
                    continue;
                }
                int found = remaining.indexOf(slot.item);
                if (found < 0) {
                    return false;
                }
                remaining.remove(found);
            }
            return remaining.isEmpty();
        }

        private boolean matchesRepair(final CraftingGrid grid) {
            if (grid.ingredientCount != 2) {
                return false;
            }
            Slot first = null;
            for (Slot slot : grid.slots) {
                if (slot.isEmpty()) {
                    continue;
                }
                if (slot.item != this.matchItem || slot.count != 1) {
                    return false;
                }
                if (first == null) {
                    first = slot;
                } else if (first.item != slot.item) {
                    return false;
                }
            }
            return first != null;
        }

        private boolean matchesImpureMap(final CraftingGrid grid, final WorldState world) {
            boolean hasMap = false;
            boolean hasPaper = false;
            for (Slot slot : grid.slots) {
                if (slot.isEmpty()) {
                    continue;
                }
                if (slot.item == MAP_ITEM && slot.component != 0) {
                    hasMap = true;
                } else if (slot.item == PAPER) {
                    hasPaper = true;
                } else {
                    return false;
                }
            }
            return hasMap && hasPaper && world.mapExpandable;
        }
    }

    private static boolean isSymmetrical(final int width, final int height, final int[] pattern) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pattern[x + y * width] != pattern[width - x - 1 + y * width]) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Slot(int item, int component, int count) {
        boolean isEmpty() {
            return this.item == 0 || this.count <= 0;
        }
    }

    private static final class CraftingGrid {
        final int width;
        final int height;
        final Slot[] slots;
        final int ingredientCount;

        private CraftingGrid(final int width, final int height, final Slot[] slots, final int ingredientCount) {
            this.width = width;
            this.height = height;
            this.slots = slots;
            this.ingredientCount = ingredientCount;
        }

        static CraftingGrid shrink(final int[][] raw) {
            int left = 2;
            int right = 0;
            int top = 2;
            int bottom = 0;
            boolean any = false;
            for (int y = 0; y < 3; y++) {
                boolean rowEmpty = true;
                for (int x = 0; x < 3; x++) {
                    Slot slot = slotAt(raw, x, y);
                    if (!slot.isEmpty()) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        rowEmpty = false;
                        any = true;
                    }
                }
                if (!rowEmpty) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
            if (!any) {
                return new CraftingGrid(0, 0, new Slot[0], 0);
            }
            int width = right - left + 1;
            int height = bottom - top + 1;
            Slot[] slots = new Slot[width * height];
            int occupied = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    Slot slot = slotAt(raw, x + left, y + top);
                    slots[x + y * width] = slot;
                    if (!slot.isEmpty()) {
                        occupied++;
                    }
                }
            }
            return new CraftingGrid(width, height, slots, occupied);
        }

        private static Slot slotAt(final int[][] raw, final int x, final int y) {
            int[] cell = raw[x + y * 3];
            return new Slot(cell[0], cell[1], cell[2]);
        }

        boolean isEmpty() {
            return this.ingredientCount == 0;
        }

        int size() {
            return this.slots.length;
        }

        Slot get(final int x, final int y) {
            return this.slots[x + y * this.width];
        }
    }

    private record MatchResult(int recipeId, int outputId, int outputCount, int[] remnants, int furnaceItem, int furnaceComponent) {
        static MatchResult from(final ModelRecipe recipe, final CraftingGrid grid) {
            int[] remnants = new int[grid.size()];
            for (int i = 0; i < grid.slots.length; i++) {
                remnants[i] = grid.slots[i].item == WATER_BUCKET ? BUCKET : 0;
            }
            return new MatchResult(recipe.id, recipe.outputId, recipe.outputCount, remnants, 0, 0);
        }

        static MatchResult none(final CraftingGrid grid) {
            int[] remnants = new int[grid.size()];
            for (int i = 0; i < grid.slots.length; i++) {
                remnants[i] = grid.slots[i].item;
            }
            return new MatchResult(0, 0, 0, remnants, 0, 0);
        }

        static MatchResult fromCooking(final ModelRecipe recipe, final Slot input) {
            return new MatchResult(recipe.id, recipe.outputId, recipe.outputCount, new int[0], input.item, input.component);
        }

        static MatchResult noneFurnace(final Slot input) {
            return new MatchResult(0, 0, 0, new int[0], input.item, input.component);
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof MatchResult other)) {
                return false;
            }
            return this.recipeId == other.recipeId
                    && this.outputId == other.outputId
                    && this.outputCount == other.outputCount
                    && Arrays.equals(this.remnants, other.remnants)
                    && this.furnaceItem == other.furnaceItem
                    && this.furnaceComponent == other.furnaceComponent;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.recipeId, this.outputId, this.outputCount, Arrays.hashCode(this.remnants), this.furnaceItem, this.furnaceComponent);
        }
    }

    private record BrewMix(boolean container, int fromItem, int fromPotion, int ingredientId, int toItem, int toPotion) {
        static BrewMix container(final int fromItem, final int ingredientId, final int toItem) {
            return new BrewMix(true, fromItem, 0, ingredientId, toItem, 0);
        }

        static BrewMix potion(final int fromItem, final int fromPotion, final int ingredientId, final int toPotion) {
            return new BrewMix(false, fromItem, fromPotion, ingredientId, fromItem, toPotion);
        }

        boolean matches(final Slot source, final Slot ingredient) {
            if (ingredient.item != this.ingredientId) {
                return false;
            }
            if (this.container) {
                return source.item == this.fromItem;
            }
            return source.component != 0 && source.item == this.fromItem && source.component == this.fromPotion;
        }

        Slot apply(final Slot source) {
            if (this.container) {
                return new Slot(this.toItem, source.component, 1);
            }
            return new Slot(source.item, this.toPotion, 1);
        }
    }

    private record BrewResult(boolean isIngredient, boolean hasMix, Slot mixed) {
    }

    private static final class CachedScanner {
        private List<ModelRecipe> recipes;
        private List<BrewMix> brewMixes = List.of();
        WorldState world;
        final FirstMatchTable<Object, MatchResult> craftingTable = new FirstMatchTable<>();
        final FirstMatchTable<Object, MatchResult> furnaceTable = new FirstMatchTable<>();
        final FirstMatchTable<Object, BrewResult> brewingTable = new FirstMatchTable<>();

        CachedScanner(final List<ModelRecipe> recipes, final WorldState world) {
            this.recipes = recipes;
            this.world = world;
        }

        void reload(final List<ModelRecipe> recipes) {
            this.recipes = recipes;
            this.craftingTable.invalidate();
            this.furnaceTable.invalidate();
            this.brewingTable.invalidate();
        }

        MatchResult craft(final int[][] raw) {
            CraftingGrid grid = CraftingGrid.shrink(raw);
            if (shouldBypassCrafting(grid)) {
                return vanillaCraft(this.recipes, raw, this.world);
            }
            Object key = craftingKey(grid);
            MatchResult cached = this.craftingTable.get(key);
            if (cached != null) {
                return cached;
            }
            MatchResult scanned = vanillaCraft(this.recipes, raw, this.world);
            this.craftingTable.put(key, scanned);
            return scanned;
        }

        MatchResult cook(final Slot input) {
            Object key = furnaceKey(input);
            MatchResult cached = this.furnaceTable.get(key);
            if (cached != null) {
                return cached;
            }
            MatchResult scanned = vanillaCook(this.recipes, input);
            this.furnaceTable.put(key, scanned);
            return scanned;
        }

        BrewResult brew(final Slot source, final Slot ingredient) {
            Object key = brewKey(source, ingredient);
            BrewResult cached = this.brewingTable.get(key);
            if (cached != null) {
                return cached;
            }
            BrewResult scanned = vanillaBrew(this.brewMixes, source, ingredient);
            this.brewingTable.put(key, scanned);
            return scanned;
        }

        private boolean shouldBypassCrafting(final CraftingGrid grid) {
            boolean hasImpure = false;
            boolean mapPresent = false;
            for (ModelRecipe recipe : this.recipes) {
                if (recipe.impure) {
                    hasImpure = true;
                }
            }
            for (Slot slot : grid.slots) {
                if (slot.item == MAP_ITEM && slot.component != 0) {
                    mapPresent = true;
                    break;
                }
            }
            return hasImpure && mapPresent;
        }

        private static Object craftingKey(final CraftingGrid grid) {
            return Arrays.asList(grid.width, grid.height, Arrays.asList(grid.slots));
        }

        private static Object furnaceKey(final Slot input) {
            return Arrays.asList(input.item, input.component);
        }

        private static Object brewKey(final Slot source, final Slot ingredient) {
            return Arrays.asList(source.item, source.component, ingredient.item, ingredient.component);
        }
    }
}
