package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import dev.ultima.inventory.SlotMaskQueries;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin implements Container {
    @WrapMethod(method = "setItem")
    private void ultimaSetItem(final int slot, final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runIndexedWrite(this, slot, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "removeItem")
    private ItemStack ultimaRemoveItem(final int slot, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite(this, slot, () -> original.call(slot, count));
    }

    @WrapMethod(method = "removeItemNoUpdate")
    private ItemStack ultimaRemoveNoUpdate(final int slot, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite(this, slot, () -> original.call(slot));
    }

    @WrapMethod(method = "clearContent")
    private void ultimaClear(final Operation<Void> original) {
        SlotMaskHooks.runClear(this, original::call);
    }

    @WrapMethod(method = "setItems")
    private void ultimaSetItems(final NonNullList<ItemStack> items, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(items));
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        Boolean empty = SlotMaskQueries.tryExactEmpty(this);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }
}
