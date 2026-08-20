package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.failopen.FailOpenGuard;
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
 *
 * <p>Fail-open for extract lives in {@link SlotMaskQueries#filterOccupiedPreservingOrder}.
 * Do not wrap that call in another {@link FailOpenGuard} door: the inner wrapper swallows
 * the throw, and an outer {@code supply} would {@code recordSuccess} and reset the breaker.
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
        int[] slots = original.call(container, direction);
        return SlotMaskQueries.filterOccupiedPreservingOrder(container, slots);
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
        boolean skipEmpty = FailOpenGuard.test(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK,
                self,
                () -> VanillaSlotMaskAllowlist.mayConsume(self)
                        && SlotMaskTracker.of(self).trusted()
                        && !SlotMaskTracker.of(self).hintedOccupied(slot),
                false);
        if (skipEmpty) {
            return ItemStack.EMPTY;
        }
        return original.call(self, slot);
    }
}
