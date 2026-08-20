package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ListBackedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ListBackedContainer.class)
public interface ListBackedContainerMixin {
    @WrapMethod(method = "setItem")
    default void ultimaSetItem(final int slot, final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runIndexedWrite((Container)(Object)this, slot, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "removeItem")
    default ItemStack ultimaRemoveItem(final int slot, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite((Container)(Object)this, slot, () -> original.call(slot, count));
    }

    @WrapMethod(method = "clearContent")
    default void ultimaClear(final Operation<Void> original) {
        SlotMaskHooks.runClear((Container)(Object)this, original::call);
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    default void ultimaIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        Boolean empty = dev.ultima.inventory.SlotMaskQueries.tryExactEmpty((Container)(Object)this);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }
}
