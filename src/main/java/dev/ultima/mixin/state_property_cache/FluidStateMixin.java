package dev.ultima.mixin.state_property_cache;

import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FluidState.class)
public abstract class FluidStateMixin {
    @Inject(method = "isSource()Z", at = @At("HEAD"), cancellable = true)
    private void ultimaSourceHead(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean cached = StatePropertyRuntime.fluidSourceIfCached(this.self());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "isSource()Z", at = @At("RETURN"))
    private void ultimaSourceReturn(final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberFluidSource(this.self(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaEmptyHead(final CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean cached = StatePropertyRuntime.fluidEmptyIfCached(this.self());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "isEmpty", at = @At("RETURN"))
    private void ultimaEmptyReturn(final CallbackInfoReturnable<Boolean> cir) {
        try {
            StatePropertyRuntime.rememberFluidEmpty(this.self(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "getAmount", at = @At("HEAD"), cancellable = true)
    private void ultimaAmountHead(final CallbackInfoReturnable<Integer> cir) {
        try {
            Integer cached = StatePropertyRuntime.fluidAmountIfCached(this.self());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "getAmount", at = @At("RETURN"))
    private void ultimaAmountReturn(final CallbackInfoReturnable<Integer> cir) {
        try {
            StatePropertyRuntime.rememberFluidAmount(this.self(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "getOwnHeight", at = @At("HEAD"), cancellable = true)
    private void ultimaHeightHead(final CallbackInfoReturnable<Float> cir) {
        try {
            Float cached = StatePropertyRuntime.fluidHeightIfCached(this.self());
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Inject(method = "getOwnHeight", at = @At("RETURN"))
    private void ultimaHeightReturn(final CallbackInfoReturnable<Float> cir) {
        try {
            StatePropertyRuntime.rememberFluidHeight(this.self(), cir.getReturnValue());
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, this.self(), error);
        }
    }

    @Unique
    private FluidState self() {
        return (FluidState)(Object)this;
    }
}
