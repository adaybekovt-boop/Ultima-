package dev.ultima.review;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * JavaExec cannot run datapack component binding ({@code ReloadableServerResources}).
 * Occupancy and recipe-key tests only need a non-empty {@link ItemStack}: {@code isEmpty()}
 * is identity/air/count, not component contents. Binding {@link DataComponentMap#EMPTY} onto
 * dirt's built-in holder is enough to construct that stack.
 */
public final class MinecraftTestItems {
    private static boolean ready;

    private MinecraftTestItems() {
    }

    public static ItemStack dirt() {
        ensureBootstrapped("non-empty dirt ItemStack");
        return new ItemStack(Items.DIRT);
    }

    public static void ensureBootstrapped(final String purpose) {
        if (ready) {
            return;
        }
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            Holder.Reference<Item> holder = Items.DIRT.builtInRegistryHolder();
            if (!holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        } catch (Throwable error) {
            throw new AssertionError("Minecraft bootstrap is required for " + purpose, error);
        }
        ItemStack probe = new ItemStack(Items.DIRT);
        if (probe.isEmpty()) {
            throw new AssertionError(
                    "Minecraft bootstrap did not produce a non-empty ItemStack for " + purpose);
        }
        ready = true;
    }
}
