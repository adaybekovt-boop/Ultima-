package dev.ultima.recipe;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BannerDuplicateRecipe;
import net.minecraft.world.item.crafting.BookCloningRecipe;
import net.minecraft.world.item.crafting.DecoratedPotRecipe;
import net.minecraft.world.item.crafting.DyeRecipe;
import net.minecraft.world.item.crafting.FireworkRocketRecipe;
import net.minecraft.world.item.crafting.FireworkStarFadeRecipe;
import net.minecraft.world.item.crafting.FireworkStarRecipe;
import net.minecraft.world.item.crafting.ImbueRecipe;
import net.minecraft.world.item.crafting.MapExtendingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.ShieldDecorationRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;

/**
 * Cacheability plan rebuilt on every {@code RecipeManager.apply} ({@code /reload} / datapack / recipe
 * registry replacement).
 *
 * <p>26.2 matching does not consume RNG. {@code Level} is unused by every vanilla recipe except
 * {@link MapExtendingRecipe}, which reads map saved data. Unknown or special classes that cannot
 * be proven to be a pure function of {@code (type, input)} force a vanilla scan.
 */
public final class RecipeCachePolicy {
    public static final RecipeCachePolicy EMPTY = new RecipeCachePolicy(Map.of());

    private final Map<RecipeType<?>, TypePlan> plans;

    private RecipeCachePolicy(final Map<RecipeType<?>, TypePlan> plans) {
        this.plans = plans;
    }

    public static RecipeCachePolicy inspect(final RecipeMap recipes) {
        Map<RecipeType<?>, TypePlan> plans = new IdentityHashMap<>();
        for (RecipeHolder<?> holder : recipes.values()) {
            Recipe<?> recipe = holder.value();
            TypePlan plan = plans.computeIfAbsent(recipe.getType(), type -> new TypePlan());
            classify(recipe, plan);
        }
        return new RecipeCachePolicy(plans);
    }

    public boolean shouldBypassCache(final RecipeType<?> type, final RecipeInput input) {
        TypePlan plan = this.plans.get(type);
        if (plan == null) {
            return false;
        }
        if (plan.neverCache) {
            return true;
        }
        return plan.mapExtendingPresent && RecipeMatchKeys.containsFilledMap(input);
    }

    static Purity purityOf(final Recipe<?> recipe) {
        if (recipe instanceof MapExtendingRecipe) {
            return Purity.IMPURE;
        }
        if (recipe instanceof ShapedRecipe
                || recipe instanceof ShapelessRecipe
                || recipe instanceof TransmuteRecipe
                || recipe instanceof DyeRecipe
                || recipe instanceof ImbueRecipe
                || recipe instanceof AbstractCookingRecipe
                || recipe instanceof SingleItemRecipe
                || recipe instanceof SmithingRecipe
                || recipe instanceof RepairItemRecipe
                || recipe instanceof FireworkRocketRecipe
                || recipe instanceof FireworkStarRecipe
                || recipe instanceof FireworkStarFadeRecipe
                || recipe instanceof BookCloningRecipe
                || recipe instanceof ShieldDecorationRecipe
                || recipe instanceof BannerDuplicateRecipe
                || recipe instanceof DecoratedPotRecipe) {
            return Purity.PURE;
        }
        return Purity.IMPURE;
    }

    private static void classify(final Recipe<?> recipe, final TypePlan plan) {
        Purity purity = purityOf(recipe);
        if (purity == Purity.IMPURE) {
            if (recipe instanceof MapExtendingRecipe) {
                plan.mapExtendingPresent = true;
            } else {
                plan.neverCache = true;
            }
        }
    }

    enum Purity {
        PURE,
        IMPURE
    }

    static final class TypePlan {
        boolean neverCache;
        boolean mapExtendingPresent;
    }
}
