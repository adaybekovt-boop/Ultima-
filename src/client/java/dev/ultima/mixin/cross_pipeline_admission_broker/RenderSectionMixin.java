package dev.ultima.mixin.cross_pipeline_admission_broker;

import dev.ultima.client.broker.CrossPipelineBroker;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tracks the bounded initial-build backlog without retaining section objects in an external map. */
@Mixin(value = RenderSection.class, remap = false)
public abstract class RenderSectionMixin {
    @Unique private boolean ultima$initialBuildOutstanding;
    @Unique private long ultima$initialBuildRequestedNanos;

    @Shadow public abstract boolean isBuilt();

    @Inject(method = "setPendingUpdate", at = @At("HEAD"), require = 0)
    private void ultima$trackInitialBuild(final int type, final long now, final CallbackInfo ci) {
        if (!this.ultima$initialBuildOutstanding
                && !this.isBuilt()
                && ChunkUpdateTypes.isInitialBuild(type)) {
            this.ultima$initialBuildOutstanding = true;
            this.ultima$initialBuildRequestedNanos = now;
            CrossPipelineBroker.initialBuildRequested();
        }
    }

    @Inject(method = "setInfo", at = @At("RETURN"), require = 0)
    private void ultima$trackFirstRenderable(
            final BuiltSectionInfo info, final CallbackInfoReturnable<Integer> cir) {
        if (this.ultima$initialBuildOutstanding && this.isBuilt()) {
            long age = Math.max(0L, System.nanoTime() - this.ultima$initialBuildRequestedNanos);
            this.ultima$initialBuildOutstanding = false;
            CrossPipelineBroker.initialBuildRenderable(age);
        }
    }

    @Inject(method = "delete", at = @At("HEAD"), require = 0)
    private void ultima$cancelInitialBuildTracking(final CallbackInfo ci) {
        if (this.ultima$initialBuildOutstanding) {
            this.ultima$initialBuildOutstanding = false;
            CrossPipelineBroker.initialBuildCancelled();
        }
    }
}
