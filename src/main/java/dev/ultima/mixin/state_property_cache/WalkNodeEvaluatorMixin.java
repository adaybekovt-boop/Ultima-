package dev.ultima.mixin.state_property_cache;

import dev.ultima.cache.state.StatePropertyRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code getPathTypeFromState} reads the world only to fetch the BlockState, then classifies from
 * that state (tags, block identity, state properties). Fabric {@code LandPathTypeRegistry}
 * providers are treated as unproven: those blocks never enter the cache.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private static void ultimaPathTypeHead(
            final BlockGetter level, final BlockPos pos, final CallbackInfoReturnable<PathType> cir) {
        BlockState state = level.getBlockState(pos);
        PathType cached = StatePropertyRuntime.pathTypeIfCached(state);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getPathTypeFromState", at = @At("RETURN"))
    private static void ultimaPathTypeReturn(
            final BlockGetter level, final BlockPos pos, final CallbackInfoReturnable<PathType> cir) {
        StatePropertyRuntime.rememberPathType(level.getBlockState(pos), cir.getReturnValue());
    }
}
