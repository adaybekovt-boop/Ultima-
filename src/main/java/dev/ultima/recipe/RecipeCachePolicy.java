package dev.ultima.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.item.crafting.BannerDuplicateRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.BookCloningRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
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
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;

/**
 * Cacheability plan rebuilt on every {@code RecipeManager.apply} ({@code /reload} / datapack / recipe
 * registry replacement).
 *
 * <p>26.2 matching does not consume RNG. {@code matches(CraftingInput, Level)} bytecode for every
 * allowlisted class leaves the {@code Level} local unread. {@link MapExtendingRecipe} is the
 * exception: it calls {@code MapItem.getSavedData} and is not allowlisted. Unknown classes, extra
 * instance fields, and mixin-merged methods are not cached. One unknown recipe does not disable
 * the whole {@link RecipeType}: only holders strictly before the first unsafe recipe may be stored,
 * and a miss is stored only when every recipe of that type is an exact known class.
 */
public final class RecipeCachePolicy {
    public static final RecipeCachePolicy EMPTY = new RecipeCachePolicy(Map.of());

    private final Map<RecipeType<?>, TypePlan> plans;

    private static final Set<Class<?>> EXACT_PURE = Set.of(
            ShapedRecipe.class,
            ShapelessRecipe.class,
            TransmuteRecipe.class,
            DyeRecipe.class,
            ImbueRecipe.class,
            SmeltingRecipe.class,
            BlastingRecipe.class,
            SmokingRecipe.class,
            CampfireCookingRecipe.class,
            StonecutterRecipe.class,
            SmithingTransformRecipe.class,
            SmithingTrimRecipe.class,
            RepairItemRecipe.class,
            FireworkRocketRecipe.class,
            FireworkStarRecipe.class,
            FireworkStarFadeRecipe.class,
            BookCloningRecipe.class,
            ShieldDecorationRecipe.class,
            BannerDuplicateRecipe.class,
            DecoratedPotRecipe.class);

    private RecipeCachePolicy(final Map<RecipeType<?>, TypePlan> plans) {
        this.plans = plans;
    }

    public static RecipeCachePolicy inspect(final RecipeMap recipes) {
        Map<RecipeType<?>, List<RecipeHolder<?>>> ordered = new IdentityHashMap<>();
        for (RecipeHolder<?> holder : recipes.values()) {
            ordered.computeIfAbsent(holder.value().getType(), type -> new ArrayList<>()).add(holder);
        }
        Map<RecipeType<?>, TypePlan> plans = new IdentityHashMap<>();
        for (Map.Entry<RecipeType<?>, List<RecipeHolder<?>>> entry : ordered.entrySet()) {
            plans.put(entry.getKey(), plan(entry.getValue()));
        }
        return new RecipeCachePolicy(plans);
    }

    /**
     * Exact class plus the pinned 26.2 field shape. A subclass, an extra instance field, or a
     * mixin-merged method fails closed. This does not prove an {@code @Inject} left {@code matches}
     * untouched when it adds neither a field nor {@code MixinMerged}.
     */
    public static boolean isExactPureClass(final Class<?> recipeClass) {
        return recipeClass != null
                && EXACT_PURE.contains(recipeClass)
                && RecipeVanillaShape.matchesPinnedShape(recipeClass);
    }

    static int cacheablePrefixLength(final List<Class<?>> order) {
        int length = 0;
        for (Class<?> recipeClass : order) {
            if (!isExactPureClass(recipeClass)) {
                break;
            }
            length++;
        }
        return length;
    }

    static boolean fullyPure(final List<Class<?>> order) {
        for (Class<?> recipeClass : order) {
            if (!isExactPureClass(recipeClass)) {
                return false;
            }
        }
        return true;
    }

    public boolean shouldBypassCache(final RecipeType<?> type, final RecipeInput input) {
        TypePlan plan = this.plans.get(type);
        if (plan == null) {
            return false;
        }
        return plan.mapExtendingPresent && RecipeMatchKeys.containsFilledMap(input);
    }

    /**
     * Store a hit only when that holder is in the pure prefix. Store a miss only when the type
     * has no unsafe recipe. An unknown recipe later in the list must not be cached and must not
     * erase earlier exact matches.
     */
    public boolean mayStore(
            final RecipeType<?> type,
            final RecipeInput input,
            final Optional<? extends RecipeHolder<?>> result) {
        if (result == null || shouldBypassCache(type, input)) {
            return false;
        }
        TypePlan plan = this.plans.get(type);
        if (plan == null) {
            return result.isEmpty();
        }
        if (result.isEmpty()) {
            return plan.fullyPure;
        }
        return plan.cacheableHits.contains(result.get());
    }

    private static TypePlan plan(final List<RecipeHolder<?>> holders) {
        TypePlan plan = new TypePlan();
        boolean unsafe = false;
        for (RecipeHolder<?> holder : holders) {
            Recipe<?> recipe = holder.value();
            if (isExactPureClass(recipe.getClass())) {
                if (!unsafe) {
                    plan.cacheableHits.add(holder);
                }
                continue;
            }
            unsafe = true;
            plan.fullyPure = false;
            if (recipe instanceof MapExtendingRecipe) {
                plan.mapExtendingPresent = true;
            }
        }
        return plan;
    }

    static final class TypePlan {
        boolean fullyPure = true;
        boolean mapExtendingPresent;
        final Set<RecipeHolder<?>> cacheableHits = Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
