package dev.ultima.mixin.blockentity_sleeping;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {
    @Accessor("cooldownTime")
    int ultima$getCooldownTime();

    @Accessor("items")
    NonNullList<ItemStack> ultima$getItems();
}
