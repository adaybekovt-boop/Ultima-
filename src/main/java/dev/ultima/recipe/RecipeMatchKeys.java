package dev.ultima.recipe;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;

/**
 * Exact cache keys for 26.2 first-match lookups.
 *
 * <p>Shapeless recipes in 26.2 match via {@code StackedItemContents} and do not depend on slot
 * order, but the {@code crafting} type also contains shaped and special recipes. Canonicalizing
 * shapeless order would collide distinct first-matches, so crafting keys always keep geometry.
 */
public final class RecipeMatchKeys {
    private RecipeMatchKeys() {
    }

    public static Object keyFor(final RecipeType<?> type, final RecipeInput input) {
        if (input instanceof CraftingInput crafting) {
            return CraftingKey.from(type, crafting);
        }
        if (input instanceof SingleRecipeInput single) {
            return FurnaceKey.from(type, single);
        }
        if (input instanceof SmithingRecipeInput smithing) {
            return SmithingKey.from(type, smithing);
        }
        return null;
    }

    public static boolean containsFilledMap(final RecipeInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            if (input.getItem(slot).has(DataComponents.MAP_ID)) {
                return true;
            }
        }
        return false;
    }

    public record CraftingKey(RecipeType<?> type, int width, int height, CapturedStack[] slots, int hash) {
        public static CraftingKey from(final RecipeType<?> type, final CraftingInput input) {
            CapturedStack[] slots = new CapturedStack[input.size()];
            int hash = 31 * System.identityHashCode(type) + input.width();
            hash = 31 * hash + input.height();
            for (int i = 0; i < slots.length; i++) {
                CapturedStack captured = CapturedStack.ofMatching(input.getItem(i));
                slots[i] = captured;
                hash = 31 * hash + captured.hashCode();
            }
            return new CraftingKey(type, input.width(), input.height(), slots, hash);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CraftingKey other)) {
                return false;
            }
            return this.type == other.type
                    && this.width == other.width
                    && this.height == other.height
                    && Arrays.equals(this.slots, other.slots);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    public record FurnaceKey(RecipeType<?> type, CapturedStack input, int hash) {
        public static FurnaceKey from(final RecipeType<?> type, final SingleRecipeInput input) {
            CapturedStack captured = CapturedStack.ofIdentity(input.item());
            return new FurnaceKey(type, captured, 31 * System.identityHashCode(type) + captured.hashCode());
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FurnaceKey other)) {
                return false;
            }
            return this.type == other.type && Objects.equals(this.input, other.input);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    public record SmithingKey(RecipeType<?> type, CapturedStack template, CapturedStack base, CapturedStack addition, int hash) {
        public static SmithingKey from(final RecipeType<?> type, final SmithingRecipeInput input) {
            CapturedStack template = CapturedStack.ofMatching(input.template());
            CapturedStack base = CapturedStack.ofMatching(input.base());
            CapturedStack addition = CapturedStack.ofMatching(input.addition());
            int hash = 31 * System.identityHashCode(type) + template.hashCode();
            hash = 31 * hash + base.hashCode();
            hash = 31 * hash + addition.hashCode();
            return new SmithingKey(type, template, base, addition, hash);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SmithingKey other)) {
                return false;
            }
            return this.type == other.type
                    && Objects.equals(this.template, other.template)
                    && Objects.equals(this.base, other.base)
                    && Objects.equals(this.addition, other.addition);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    public record BrewingKey(Kind kind, CapturedStack source, CapturedStack ingredient, int hash) {
        public static BrewingKey ingredient(final ItemStack ingredient) {
            CapturedStack captured = CapturedStack.ofIdentity(ingredient);
            return new BrewingKey(Kind.INGREDIENT, CapturedStack.EMPTY, captured, 31 * Kind.INGREDIENT.hashCode() + captured.hashCode());
        }

        public static BrewingKey pair(final Kind kind, final ItemStack source, final ItemStack ingredient) {
            CapturedStack capturedSource = CapturedStack.ofIdentity(source);
            CapturedStack capturedIngredient = CapturedStack.ofIdentity(ingredient);
            int hash = 31 * kind.hashCode() + capturedSource.hashCode();
            hash = 31 * hash + capturedIngredient.hashCode();
            return new BrewingKey(kind, capturedSource, capturedIngredient, hash);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BrewingKey other)) {
                return false;
            }
            return this.kind == other.kind
                    && Objects.equals(this.source, other.source)
                    && Objects.equals(this.ingredient, other.ingredient);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        public enum Kind {
            INGREDIENT,
            HAS_MIX,
            MIX
        }
    }
}
