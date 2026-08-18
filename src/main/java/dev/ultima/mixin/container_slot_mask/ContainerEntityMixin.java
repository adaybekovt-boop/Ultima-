package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.SlotMaskHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerEntity.class)
public interface ContainerEntityMixin {
    @Inject(method = "setChestVehicleItem", at = @At("HEAD"))
    private void ultimaBeforeSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "setChestVehicleItem", at = @At("RETURN"))
    private void ultimaAfterSetItem(final int slot, final ItemStack itemStack, final CallbackInfo ci) {
        SlotMaskHooks.afterIndexedWrite((Container)(Object)this, slot);
    }

    @Inject(method = "removeChestVehicleItem", at = @At("HEAD"))
    private void ultimaBeforeRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "removeChestVehicleItem", at = @At("RETURN"))
    private void ultimaAfterRemoveItem(final int slot, final int count, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite((Container)(Object)this, slot);
    }

    @Inject(method = "removeChestVehicleItemNoUpdate", at = @At("HEAD"))
    private void ultimaBeforeRemoveNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "removeChestVehicleItemNoUpdate", at = @At("RETURN"))
    private void ultimaAfterRemoveNoUpdate(final int slot, final CallbackInfoReturnable<ItemStack> cir) {
        SlotMaskHooks.afterIndexedWrite((Container)(Object)this, slot);
    }

    @Inject(method = "clearChestVehicleContent", at = @At("HEAD"))
    private void ultimaBeforeClear(final CallbackInfo ci) {
        SlotMaskHooks.beforeIndexedWrite((Container)(Object)this);
    }

    @Inject(method = "clearChestVehicleContent", at = @At("RETURN"))
    private void ultimaAfterClear(final CallbackInfo ci) {
        SlotMaskHooks.afterClearWrapped((Container)(Object)this);
    }

    @Inject(method = "readChestVehicleSaveData", at = @At("RETURN"))
    private void ultimaAfterLoad(final net.minecraft.world.level.storage.ValueInput input, final CallbackInfo ci) {
        SlotMaskHooks.invalidate((Container)(Object)this);
    }
}
