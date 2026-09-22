package dev.ultima.recipe;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pinned 26.2 instance-field sets for the exact recipe classes Ultima is willing to cache.
 *
 * <p>An exact {@link Class} does not prove a mixin left {@code matches} alone. A mixin that adds an
 * instance field, or that merges a method ({@code MixinMerged}), fails closed for that class.
 * An {@code @Inject} that rewrites {@code matches} without adding a field or a merged method is not
 * visible this way; unknown recipes still bypass the cache, and this check is the practical
 * conservative signal short of a runtime bytecode dump.
 */
final class RecipeVanillaShape {
    static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";

    /**
     * Declared instance fields of the class itself, not its parents. Parents are listed separately
     * and walked at check time. Empty means the vanilla class has no instance fields.
     */
    private static final Map<String, Set<String>> INSTANCE_FIELDS = Map.ofEntries(
            Map.entry("net.minecraft.world.item.crafting.ShapedRecipe", Set.of("pattern", "result")),
            Map.entry("net.minecraft.world.item.crafting.ShapelessRecipe", Set.of("result", "ingredients")),
            Map.entry(
                    "net.minecraft.world.item.crafting.TransmuteRecipe",
                    Set.of("input", "material", "materialCount", "result", "addMaterialCountToResult")),
            Map.entry("net.minecraft.world.item.crafting.DyeRecipe", Set.of("target", "dye", "result")),
            Map.entry("net.minecraft.world.item.crafting.ImbueRecipe", Set.of("source", "material", "result")),
            Map.entry("net.minecraft.world.item.crafting.SmeltingRecipe", Set.of()),
            Map.entry("net.minecraft.world.item.crafting.BlastingRecipe", Set.of()),
            Map.entry("net.minecraft.world.item.crafting.SmokingRecipe", Set.of()),
            Map.entry("net.minecraft.world.item.crafting.CampfireCookingRecipe", Set.of()),
            Map.entry("net.minecraft.world.item.crafting.StonecutterRecipe", Set.of()),
            Map.entry(
                    "net.minecraft.world.item.crafting.SmithingTransformRecipe",
                    Set.of("template", "base", "addition", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.SmithingTrimRecipe",
                    Set.of("template", "base", "addition", "pattern")),
            Map.entry("net.minecraft.world.item.crafting.RepairItemRecipe", Set.of()),
            Map.entry(
                    "net.minecraft.world.item.crafting.FireworkRocketRecipe",
                    Set.of("shell", "fuel", "star", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.FireworkStarRecipe",
                    Set.of("shapes", "trail", "twinkle", "fuel", "dye", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.FireworkStarFadeRecipe",
                    Set.of("target", "dye", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.BookCloningRecipe",
                    Set.of("source", "material", "allowedGenerations", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.ShieldDecorationRecipe",
                    Set.of("banner", "target", "result")),
            Map.entry("net.minecraft.world.item.crafting.BannerDuplicateRecipe", Set.of("banner", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.DecoratedPotRecipe",
                    Set.of("backPattern", "leftPattern", "rightPattern", "frontPattern", "result")),
            Map.entry(
                    "net.minecraft.world.item.crafting.NormalCraftingRecipe",
                    Set.of("commonInfo", "bookInfo", "placementInfo")),
            Map.entry("net.minecraft.world.item.crafting.CustomRecipe", Set.of()),
            Map.entry(
                    "net.minecraft.world.item.crafting.AbstractCookingRecipe",
                    Set.of("bookInfo", "experience", "cookingTime")),
            Map.entry(
                    "net.minecraft.world.item.crafting.SingleItemRecipe",
                    Set.of("commonInfo", "input", "result", "placementInfo")),
            Map.entry(
                    "net.minecraft.world.item.crafting.SimpleSmithingRecipe",
                    Set.of("commonInfo", "placementInfo")));

    private static final ClassValue<Boolean> SHAPE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            return matchesUncached(type);
        }
    };

    private RecipeVanillaShape() {
    }

    static boolean matchesPinnedShape(final Class<?> type) {
        return type != null && SHAPE.get(type);
    }

    static boolean instanceFieldsMatch(final Class<?> type, final Set<String> expected) {
        return instanceFieldNames(type).equals(expected);
    }

    static boolean declaresMixinMerge(final Class<?> type) {
        return mixinMerged(type);
    }

    private static boolean matchesUncached(final Class<?> leaf) {
        Class<?> current = leaf;
        while (current != null && current != Object.class) {
            Set<String> expected = INSTANCE_FIELDS.get(current.getName());
            if (expected == null || !instanceFieldsMatch(current, expected) || mixinMerged(current)) {
                return false;
            }
            current = current.getSuperclass();
        }
        return leaf.getName().startsWith("net.minecraft.");
    }

    static Set<String> instanceFieldNames(final Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            names.add(field.getName());
        }
        return Set.copyOf(names);
    }

    private static boolean mixinMerged(final Class<?> type) {
        try {
            if (named(type.getDeclaredAnnotations())) {
                return true;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (named(method.getDeclaredAnnotations())) {
                    return true;
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (named(field.getDeclaredAnnotations())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean named(final Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (MIXIN_MERGED.equals(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }
}
