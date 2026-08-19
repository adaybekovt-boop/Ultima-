package dev.ultima.mixin.state_property_cache;

import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(method = "isSignalSource", at = @At("HEAD"), cancellable = true)
    private void ultimaSignalHead(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean cached = StatePropertyRuntime.signalSourceIfCached(this.asBlockState());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "isSignalSource", at = @At("RETURN"))
    private void ultimaSignalReturn(final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberSignalSource(this.asBlockState(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "hasAnalogOutputSignal", at = @At("HEAD"), cancellable = true)
    private void ultimaAnalogHead(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean cached = StatePropertyRuntime.analogIfCached(this.asBlockState());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "hasAnalogOutputSignal", at = @At("RETURN"))
    private void ultimaAnalogReturn(final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberAnalog(this.asBlockState(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "isRedstoneConductor", at = @At("HEAD"), cancellable = true)
    private void ultimaRedstoneHead(
            final BlockGetter level, final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.asBlockState();
        try {
            if (!StatePropertyRuntime.mayCacheRedstoneConductor(state)) {
                StatePropertyRuntime.noteUncacheableRedstoneConductor(state);
                return;
            }
            Boolean cached = StatePropertyRuntime.redstoneConductorIfCached(state);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, error);
        }
    }

    @Inject(method = "isRedstoneConductor", at = @At("RETURN"))
    private void ultimaRedstoneReturn(
            final BlockGetter level, final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberRedstoneConductor(this.asBlockState(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "isPathfindable", at = @At("HEAD"), cancellable = true)
    private void ultimaPathfindableHead(
            final PathComputationType type, final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean cached = StatePropertyRuntime.pathfindableIfCached(this.asBlockState(), type);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Inject(method = "isPathfindable", at = @At("RETURN"))
    private void ultimaPathfindableReturn(
            final PathComputationType type, final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberPathfindable(this.asBlockState(), type, cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.asBlockState(), error);
        }
    }

    @Unique
    private BlockState asBlockState() {
        return (BlockState)(Object)this;
    }
}
