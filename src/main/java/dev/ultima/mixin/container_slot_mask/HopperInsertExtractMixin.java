package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.inventory.SlotMaskQueries;
import dev.ultima.inventory.SlotMaskTracker;
import dev.ultima.inventory.VanillaSlotMaskAllowlist;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hopper insert/extract consumes the slot mask only by skipping empty slots in vanilla order.
 * This is not hopper sleeping: tick and cooldown behaviour is unchanged.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperInsertExtractMixin {
    @WrapOperation(
            method = "suckInItems",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getSlots(Lnet/minecraft/world/Container;Lnet/minecraft/core/Direction;)[I"))
    private static int[] ultimaFilterEmptySourceSlots(
            final Container container, final Direction direction, final Operation<int[]> original) {
        return SlotMaskQueries.filterOccupiedPreservingOrder(container, original.call(container, direction));
    }

    @Inject(method = "ejectItems", at = @At("HEAD"))
    private static void ultimaPrepareHopperMask(
            final net.minecraft.world.level.Level level,
            final net.minecraft.core.BlockPos blockPos,
            final HopperBlockEntity self,
            final CallbackInfoReturnable<Boolean> cir) {
        SlotMaskQueries.shouldIterateOccupied(self);
    }

    @WrapOperation(
            method = "ejectItems",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack ultimaSkipEmptyHopperSlots(
            final HopperBlockEntity self, final int slot, final Operation<ItemStack> original) {
        if (!VanillaSlotMaskAllowlist.mayConsume(self) || !SlotMaskTracker.of(self).trusted()) {
            return original.call(self, slot);
        }
        if (!SlotMaskTracker.of(self).hintedOccupied(slot)) {
            return ItemStack.EMPTY;
        }
        return original.call(self, slot);
    }
}
