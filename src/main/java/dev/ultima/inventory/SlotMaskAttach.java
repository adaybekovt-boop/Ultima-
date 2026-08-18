package dev.ultima.inventory;

import net.minecraft.core.NonNullList;
import org.jspecify.annotations.Nullable;

public final class SlotMaskAttach {
    private SlotMaskAttach() {
    }

    public static void attach(final NonNullList<?> items, final @Nullable NonEmptySlotMask mask) {
        if (items instanceof SlotMaskList list) {
            list.ultima$setSlotMask(mask);
        }
    }
}
