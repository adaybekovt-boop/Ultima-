package dev.ultima.recipe;

import java.util.Objects;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of the item identity vanilla recipe matching observes.
 *
 * <p>Crafting keys include {@code count} because 26.2 {@code RepairItemRecipe} requires
 * {@code count == 1}. Furnace keys omit count because {@code SingleItemRecipe.matches} only calls
 * {@code Ingredient.test}, which is {@code ItemStack.is(HolderSet)} and ignores count.
 */
public final class CapturedStack {
    public static final CapturedStack EMPTY = new CapturedStack(null, DataComponentMap.EMPTY, 0);

    private final @Nullable Item item;
    private final DataComponentMap components;
    private final int count;
    private final int hash;

    private CapturedStack(final @Nullable Item item, final DataComponentMap components, final int count) {
        this.item = item;
        this.components = components;
        this.count = count;
        int hash = 31 + Objects.hashCode(item);
        hash = 31 * hash + Objects.hashCode(components);
        this.hash = 31 * hash + count;
    }

    public static CapturedStack ofMatching(final ItemStack stack) {
        if (stack.isEmpty()) {
            return EMPTY;
        }
        return new CapturedStack(stack.getItem(), stack.immutableComponents(), stack.getCount());
    }

    /**
     * Furnace/brewing matching snapshot: identity + components, count forced to 1 so stack size is
     * not part of the recipe-choice key.
     */
    public static CapturedStack ofIdentity(final ItemStack stack) {
        if (stack.isEmpty()) {
            return EMPTY;
        }
        return new CapturedStack(stack.getItem(), stack.immutableComponents(), 1);
    }

    public boolean isEmpty() {
        return this.item == null || this.count <= 0;
    }

    public @Nullable Item item() {
        return this.item;
    }

    public DataComponentMap components() {
        return this.components;
    }

    public int count() {
        return this.count;
    }

    public boolean sameItemSameComponents(final CapturedStack other) {
        return this.item == other.item && Objects.equals(this.components, other.components);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CapturedStack other)) {
            return false;
        }
        return this.count == other.count
                && this.item == other.item
                && Objects.equals(this.components, other.components);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
