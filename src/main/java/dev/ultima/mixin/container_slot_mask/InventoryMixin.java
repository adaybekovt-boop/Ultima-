package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import dev.ultima.inventory.SlotMaskQueries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin implements Container {
    @WrapMethod(method = "setItem")
    private void ultimaSetItem(final int slot, final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runIndexedWrite(this, slot, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack ultimaRemoveItem(final int slot, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite(this, slot, () -> original.call(slot, count));
    }

    @WrapMethod(method = "removeItemNoUpdate")
    private ItemStack ultimaRemoveNoUpdate(final int slot, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite(this, slot, () -> original.call(slot));
    }

    @WrapMethod(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V")
    private void ultimaIdentityRemove(final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(itemStack));
    }

    @WrapMethod(method = "clearContent")
    private void ultimaClear(final Operation<Void> original) {
        SlotMaskHooks.runClear(this, original::call);
    }

    @WrapMethod(method = "setChanged")
    private void ultimaSetChanged(final Operation<Void> original) {
        SlotMaskHooks.runUnindexedMutation(this, original::call);
    }

    @WrapMethod(method = "add(ILnet/minecraft/world/item/ItemStack;)Z")
    private boolean ultimaAdd(final int slot, final ItemStack itemStack, final Operation<Boolean> original) {
        return SlotMaskHooks.callInvalidate(this, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "dropAll")
    private void ultimaDropAll(final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, original::call);
    }

    @WrapMethod(method = "replaceWith")
    private void ultimaReplaceWith(final Inventory other, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(other));
    }

    @WrapMethod(method = "load")
    private void ultimaLoad(
            final net.minecraft.world.level.storage.ValueInput.TypedInputList<net.minecraft.world.ItemStackWithSlot> input,
            final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(input));
    }

    @WrapMethod(method = "setSelectedItem")
    private ItemStack ultimaSetSelectedItem(final ItemStack itemStack, final Operation<ItemStack> original) {
        Inventory self = (Inventory)(Object)this;
        return SlotMaskHooks.callIndexedWrite(self, self.getSelectedSlot(), () -> original.call(itemStack));
    }

    @WrapMethod(method = "addAndPickItem")
    private void ultimaAddAndPickItem(final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(itemStack));
    }

    @WrapMethod(method = "pickSlot")
    private void ultimaPickSlot(final int slot, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate(this, () -> original.call(slot));
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        Boolean empty = SlotMaskQueries.tryExactEmpty(this);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }
}
