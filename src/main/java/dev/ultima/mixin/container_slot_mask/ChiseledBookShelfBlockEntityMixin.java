package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChiseledBookShelfBlockEntity.class)
public abstract class ChiseledBookShelfBlockEntityMixin {
    @WrapMethod(method = "setItem")
    private void ultimaSetItem(final int slot, final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runIndexedWrite(
                (ChiseledBookShelfBlockEntity)(Object)this, slot, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "removeItem")
    private ItemStack ultimaRemoveItem(final int slot, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite(
                (ChiseledBookShelfBlockEntity)(Object)this, slot, () -> original.call(slot, count));
    }
}
