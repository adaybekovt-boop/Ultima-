package dev.ultima.inventory;

import org.jspecify.annotations.Nullable;

/**
 * Optional owner pointer on {@code NonNullList} so {@code set}/{@code clear} occupancy changes
 * update the container mask even when vanilla writes the list without going through
 * {@code Container.setItem}.
 */
public interface SlotMaskList {
    void ultima$setSlotMask(@Nullable NonEmptySlotMask mask);

    @Nullable NonEmptySlotMask ultima$slotMask();
}
