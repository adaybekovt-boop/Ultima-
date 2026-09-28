package dev.ultima.scenario;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;

/**
 * Resolves blocks and items by field name. A name that does not exist in this Minecraft version is
 * skipped and reported in the summary instead of failing the build, so one renamed block cannot
 * silence the whole harness.
 */
final class Palette {
    private static final TreeSet<String> MISSING = new TreeSet<>();

    private Palette() {
    }

    static Block block(final String name) {
        try {
            Field field = Blocks.class.getField(name);
            return field.get(null) instanceof Block block ? block : missing("block " + name);
        } catch (ReflectiveOperationException e) {
            return missing("block " + name);
        }
    }

    static Item item(final String name) {
        try {
            Field field = Items.class.getField(name);
            return field.get(null) instanceof Item item ? item : missingItem("item " + name);
        } catch (ReflectiveOperationException e) {
            return missingItem("item " + name);
        }
    }

    /** Every possible state of every block found under the given names, in registry-stable order. */
    static List<BlockState> states(final String... blockNames) {
        List<BlockState> states = new ArrayList<>();
        for (String name : blockNames) {
            Block block = block(name);
            if (block != null) {
                states.addAll(block.getStateDefinition().getPossibleStates());
            }
        }
        return states;
    }

    static List<Item> items(final String... itemNames) {
        List<Item> items = new ArrayList<>();
        for (String name : itemNames) {
            Item item = item(name);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    static List<String> missingNames() {
        return List.copyOf(MISSING);
    }

    private static Block missing(final String what) {
        MISSING.add(what);
        return null;
    }

    private static Item missingItem(final String what) {
        MISSING.add(what);
        return null;
    }
}
