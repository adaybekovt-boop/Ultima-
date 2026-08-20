package dev.ultima.mixin.container_slot_mask;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {
    @WrapMethod(method = "setChestVehicleItem")
    default void ultimaSetItem(final int slot, final ItemStack itemStack, final Operation<Void> original) {
        SlotMaskHooks.runIndexedWrite((Container)(Object)this, slot, () -> original.call(slot, itemStack));
    }

    @WrapMethod(method = "removeChestVehicleItem")
    default ItemStack ultimaRemoveItem(final int slot, final int count, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite((Container)(Object)this, slot, () -> original.call(slot, count));
    }

    @WrapMethod(method = "removeChestVehicleItemNoUpdate")
    default ItemStack ultimaRemoveNoUpdate(final int slot, final Operation<ItemStack> original) {
        return SlotMaskHooks.callIndexedWrite((Container)(Object)this, slot, () -> original.call(slot));
    }

    @WrapMethod(method = "clearChestVehicleContent")
    default void ultimaClear(final Operation<Void> original) {
        SlotMaskHooks.runClear((Container)(Object)this, original::call);
    }

    @WrapMethod(method = "readChestVehicleSaveData")
    default void ultimaAfterLoad(final ValueInput input, final Operation<Void> original) {
        SlotMaskHooks.runInvalidate((Container)(Object)this, () -> original.call(input));
    }

    @WrapMethod(method = "unpackChestVehicleLootTable")
    default void ultimaUnpackLootTable(
            final net.minecraft.world.entity.player.Player player, final Operation<Void> original) {
        ContainerEntity self = (ContainerEntity)(Object)this;
        SlotMaskHooks.runInvalidateIf(
                self, self.getContainerLootTable() != null, () -> original.call(player));
    }
}
