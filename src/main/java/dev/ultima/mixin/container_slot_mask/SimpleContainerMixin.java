package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleContainer.class)
public abstract class SimpleContainerMixin implements Container {
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

    @WrapMethod(method = "setChanged")
    private void ultimaSetChanged(final Operation<Void> original) {
        SlotMaskHooks.runUnindexedMutation(this, original::call);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        Boolean empty = dev.ultima.inventory.SlotMaskQueries.tryExactEmpty(this);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }

    @WrapMethod(method = "addItem")
    private ItemStack ultimaAfterAddItem(final ItemStack itemStack, final Operation<ItemStack> original) {
        return SlotMaskHooks.callInvalidate(this, () -> original.call(itemStack));
    }

    @WrapMethod(method = "removeItemType")
    private ItemStack ultimaAfterRemoveItemType(
            final net.minecraft.world.item.Item itemType, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callInvalidate(this, () -> original.call(itemType, count));
    }
}
