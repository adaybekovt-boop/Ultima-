package dev.ultima.mixin.container_slot_mask;

import dev.ultima.failopen.FailOpenGuard;
import dev.ultima.inventory.CompoundContainerViews;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompoundContainer.class)
public abstract class CompoundContainerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void ultimaInstallCompoundViews(final Container container1, final Container container2, final CallbackInfo ci) {
        ultimaInstallOnce();
    }

    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ultimaIsEmpty(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean empty = dev.ultima.inventory.SlotMaskQueries.tryExactEmpty((CompoundContainer)(Object)this);
            if (empty != null) {
                cir.setReturnValue(empty);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, this, error);
        }
    }

    @Unique
    private static void ultimaInstallOnce() {
        if (CompoundContainerViews.installed()) {
            return;
        }
        CompoundContainerViews.install(new CompoundContainerViews.Access() {
            @Override
            public Container left(final CompoundContainer compound) {
                return ((CompoundContainerAccessor)compound).ultima$container1();
            }

            @Override
            public Container right(final CompoundContainer compound) {
                return ((CompoundContainerAccessor)compound).ultima$container2();
            }
        });
    }
}
