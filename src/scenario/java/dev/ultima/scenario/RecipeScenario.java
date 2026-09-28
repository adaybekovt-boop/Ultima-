package dev.ultima.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ChunkPos;

/**
 * Looks up crafting, smelting and blasting recipes for a seeded battery of inputs, reloads the
 * data packs, and repeats the battery. A cache that survives a reload with stale entries shows up
 * as a difference between the two phases across runs. Covers the recipe match cache.
 */
final class RecipeScenario implements Scenario {
    private static final int SETTLE_TICKS = 100;
    private static final int CRAFTING_CASES = 2500;

    private final String name;
    private boolean before;

    RecipeScenario(final String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public List<ChunkPos> chunks() {
        return List.of();
    }

    @Override
    public void setup(final Run run) {
        // The battery runs on the first tick, after every scenario finished building.
    }

    @Override
    public boolean tick(final Run run, final int tick) {
        if (!this.before) {
            this.battery(run, "before");
            this.before = true;
            run.requestReloadOnce();
            return false;
        }
        if (!run.reloadSettled(SETTLE_TICKS)) {
            return false;
        }
        this.battery(run, "after");
        return true;
    }

    private void battery(final Run run, final String phase) {
        ServerLevel level = run.level();
        RecipeManager recipes = run.server().getRecipeManager();
        List<Item> pool = Palette.items("OAK_PLANKS", "STICK", "COBBLESTONE", "IRON_INGOT", "COAL", "DIAMOND",
                "OAK_LOG", "SAND", "GOLD_INGOT", "REDSTONE", "STRING", "LEATHER", "PAPER", "WHEAT", "BREAD",
                "IRON_ORE", "RAW_IRON", "GLASS", "DIRT", "CLAY_BALL", "BRICK", "QUARTZ", "COPPER_INGOT", "OAK_SLAB");
        Random random = new Random(31337);
        for (int i = 0; i < CRAFTING_CASES; i++) {
            int width = 1 + random.nextInt(3);
            int height = 1 + random.nextInt(3);
            List<ItemStack> grid = new ArrayList<>(width * height);
            for (int cell = 0; cell < width * height; cell++) {
                grid.add(pool.isEmpty() || random.nextInt(3) == 0
                        ? ItemStack.EMPTY
                        : new ItemStack(pool.get(random.nextInt(pool.size()))));
            }
            Optional<? extends RecipeHolder<?>> found =
                    recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(width, height, grid), level);
            run.digest().add(this.name, phase + " craft " + i + " " + describe(found));
        }
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            String key = String.valueOf(BuiltInRegistries.ITEM.getKey(item));
            run.digest().add(this.name, phase + " smelt " + key + " "
                    + describe(recipes.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level)));
            run.digest().add(this.name, phase + " blast " + key + " "
                    + describe(recipes.getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(stack), level)));
        }
    }

    private static String describe(final Optional<? extends RecipeHolder<?>> holder) {
        return holder.map(found -> String.valueOf(found.id())).orElse("none");
    }
}
