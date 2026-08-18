package dev.ultima.mixin.container_slot_mask;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskAttach;
import dev.ultima.inventory.SlotMaskHolder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Supplier;

@Mixin(AbstractChestBoat.class)
public abstract class AbstractChestBoatMixin implements SlotMaskHolder {
    @Shadow
    private NonNullList<ItemStack> itemStacks;

    @Unique
    private NonEmptySlotMask ultima$mask;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ultimaInit(
            final EntityType<? extends AbstractChestBoat> type,
            final Level level,
            final Supplier<Item> dropItem,
            final CallbackInfo ci) {
        this.ultima$mask = new NonEmptySlotMask(this.itemStacks.size());
        SlotMaskAttach.attach(this.itemStacks, this.ultima$mask);
    }

    @Override
    public NonEmptySlotMask ultima$slotMask() {
        if (this.ultima$mask == null) {
            this.ultima$mask = new NonEmptySlotMask(this.itemStacks.size());
            SlotMaskAttach.attach(this.itemStacks, this.ultima$mask);
            this.ultima$mask.markUntrusted();
        }
        return this.ultima$mask;
    }
}
