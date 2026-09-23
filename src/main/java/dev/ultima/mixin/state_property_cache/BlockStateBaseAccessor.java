package dev.ultima.mixin.state_property_cache;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the redstone-conductor predicate copied onto each block state. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public interface BlockStateBaseAccessor {
    @Accessor("isRedstoneConductor")
    BlockBehaviour.StatePredicate ultima$redstoneConductor();
}
