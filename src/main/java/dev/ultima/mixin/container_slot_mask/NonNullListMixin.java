package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskList;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Occupancy source of truth for list-backed vanilla inventories: every {@code set} that fills or
 * empties a slot updates the attached mask, including load, loot, furnace, and hopper list writes
 * that never call {@code Container.setItem}.
 */
@Mixin(NonNullList.class)
public abstract class NonNullListMixin<E> implements SlotMaskList {
    @Unique
    private @Nullable NonEmptySlotMask ultima$mask;

    @Override
    public void ultima$setSlotMask(final @Nullable NonEmptySlotMask mask) {
        this.ultima$mask = mask;
    }

    @Override
    public @Nullable NonEmptySlotMask ultima$slotMask() {
        return this.ultima$mask;
    }

    @Inject(method = "set(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private void ultimaOnSet(final int index, final E element, final CallbackInfoReturnable<E> cir) {
        NonEmptySlotMask mask = this.ultima$mask;
        if (mask == null) {
            return;
        }
        mask.setOccupancy(index, element instanceof ItemStack stack && !stack.isEmpty());
    }
}
