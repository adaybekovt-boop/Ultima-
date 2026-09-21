package dev.ultima.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Shared accessor used by both optional CompoundContainer integrations. */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
    @Accessor("container1")
    Container ultima$container1();

    @Accessor("container2")
    Container ultima$container2();
}
