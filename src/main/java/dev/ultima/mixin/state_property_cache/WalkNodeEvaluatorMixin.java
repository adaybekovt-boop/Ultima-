package dev.ultima.mixin.state_property_cache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.failopen.FailOpenGuard;
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
 *
 * <p>The HEAD read is reused for the single vanilla {@code getBlockState} and for the return
 * store. Vanilla still classifies that same state.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private static void ultimaPathTypeHead(
            final BlockGetter level,
            final BlockPos pos,
            final CallbackInfoReturnable<PathType> cir,
            @Share("ultimaState") final LocalRef<BlockState> shared) {
        try {
            BlockState state = level.getBlockState(pos);
            shared.set(state);
            PathType cached = StatePropertyRuntime.pathTypeIfCached(state);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, pos, error);
        }
    }

    @WrapOperation(
            method = "getPathTypeFromState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState ultimaReuseBlockState(
            final BlockGetter level,
            final BlockPos pos,
            final Operation<BlockState> original,
            @Share("ultimaState") final LocalRef<BlockState> shared) {
        BlockState already = shared.get();
        if (already != null) {
            return already;
        }
        BlockState state = original.call(level, pos);
        shared.set(state);
        return state;
    }

    @Inject(method = "getPathTypeFromState", at = @At("RETURN"))
    private static void ultimaPathTypeReturn(
            final BlockGetter level,
            final BlockPos pos,
            final CallbackInfoReturnable<PathType> cir,
            @Share("ultimaState") final LocalRef<BlockState> shared) {
        try {
            BlockState state = shared.get();
            if (state == null) {
                state = level.getBlockState(pos);
            }
            StatePropertyRuntime.rememberPathType(state, cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, pos, error);
        }
    }
}
