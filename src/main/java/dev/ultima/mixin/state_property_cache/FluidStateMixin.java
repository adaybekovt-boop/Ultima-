package dev.ultima.mixin.state_property_cache;

import dev.ultima.cache.state.StatePropertyRuntime;
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
        Boolean cached = StatePropertyRuntime.fluidSourceIfCached(this.self());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "isSource()Z", at = @At("RETURN"))
    private void ultimaSourceReturn(final CallbackInfoReturnable<Boolean> cir) {
        StatePropertyRuntime.rememberFluidSource(this.self(), cir.getReturnValue());
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaEmptyHead(final CallbackInfoReturnable<Boolean> cir) {
        Boolean cached = StatePropertyRuntime.fluidEmptyIfCached(this.self());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "isEmpty", at = @At("RETURN"))
    private void ultimaEmptyReturn(final CallbackInfoReturnable<Boolean> cir) {
        StatePropertyRuntime.rememberFluidEmpty(this.self(), cir.getReturnValue());
    }

    @Inject(method = "getAmount", at = @At("HEAD"), cancellable = true)
    private void ultimaAmountHead(final CallbackInfoReturnable<Integer> cir) {
        Integer cached = StatePropertyRuntime.fluidAmountIfCached(this.self());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getAmount", at = @At("RETURN"))
    private void ultimaAmountReturn(final CallbackInfoReturnable<Integer> cir) {
        StatePropertyRuntime.rememberFluidAmount(this.self(), cir.getReturnValue());
    }

    @Inject(method = "getOwnHeight", at = @At("HEAD"), cancellable = true)
    private void ultimaHeightHead(final CallbackInfoReturnable<Float> cir) {
        Float cached = StatePropertyRuntime.fluidHeightIfCached(this.self());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getOwnHeight", at = @At("RETURN"))
    private void ultimaHeightReturn(final CallbackInfoReturnable<Float> cir) {
        StatePropertyRuntime.rememberFluidHeight(this.self(), cir.getReturnValue());
    }

    @Unique
    private FluidState self() {
        return (FluidState)(Object)this;
    }
}
