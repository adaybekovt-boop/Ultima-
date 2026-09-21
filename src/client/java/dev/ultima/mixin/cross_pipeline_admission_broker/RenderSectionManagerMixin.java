package dev.ultima.mixin.cross_pipeline_admission_broker;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.client.broker.CrossPipelineBroker;
import java.util.Collection;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium 0.9.2 adapter. Active cancellation occurs only before deferred dequeue. */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @Shadow @Final private ChunkBuilder builder;
    @Shadow @Final private SectionStorage renderSections;
    @Shadow private DeferredTaskList taskLists;

    @Unique private boolean ultima$wasBuilt;
    @Unique private long ultima$requestStartedNanos;

    @Inject(method = "updateChunks", at = @At("HEAD"), require = 0)
    private void ultima$beginChunkUpdate(
            final Viewport viewport, final boolean updateImmediately, final CallbackInfo ci) {
        CrossPipelineBroker.beginSodiumUpdate(updateImmediately);
    }

    @Inject(method = "updateChunks", at = @At("RETURN"), require = 0)
    private void ultima$endChunkUpdate(
            final Viewport viewport, final boolean updateImmediately, final CallbackInfo ci) {
        CrossPipelineBroker.endSodiumUpdate(
                this.ultima$deferredQueueDepth(),
                this.builder.getBusyThreadCount(),
                this.builder.getTotalThreadCount());
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("HEAD"), cancellable = true, require = 0)
    private void ultima$admitDeferredBeforeDequeue(
            final ChunkJobCollector collector,
            final UploadResourceBudget uploadBudget,
            final CallbackInfo ci) {
        if (!CrossPipelineBroker.permitDeferredAdmission(
                this.ultima$deferredQueueDepth(),
                this.builder.getBusyThreadCount(),
                this.builder.getTotalThreadCount(),
                this.ultima$nextDeferredAgeNanos())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;)V",
            at = @At("HEAD"),
            require = 0)
    private void ultima$observeTaskSubmission(
            final ChunkJobCollector collector,
            final RenderSection section,
            final UploadResourceBudget uploadBudget,
            final CallbackInfo ci) {
        CrossPipelineBroker.sodiumTaskSubmitted(System.nanoTime() - section.getPendingUpdateSince());
    }

    @Inject(method = "prepareFrame", at = @At("HEAD"), require = 0)
    private void ultima$observeCamera(final Vector3dc cameraPosition, final CallbackInfo ci) {
        CrossPipelineBroker.cameraPosition(cameraPosition.x(), cameraPosition.y(), cameraPosition.z());
    }

    @Inject(method = "updateWithResult", at = @At("HEAD"), require = 0)
    private void ultima$beforeApplyResult(
            final Viewport viewport,
            final RenderSection section,
            final ChunkBuildOutput output,
            final List<RenderSection> pendingPresentPatches,
            final CallbackInfoReturnable<Integer> cir) {
        this.ultima$wasBuilt = section.isBuilt();
        this.ultima$requestStartedNanos = section.getPendingUpdateSince();
    }

    @Inject(method = "updateWithResult", at = @At("RETURN"), require = 0)
    private void ultima$afterApplyResult(
            final Viewport viewport,
            final RenderSection section,
            final ChunkBuildOutput output,
            final List<RenderSection> pendingPresentPatches,
            final CallbackInfoReturnable<Integer> cir) {
        if (!this.ultima$wasBuilt && section.isBuilt()) {
            CrossPipelineBroker.firstRenderable(System.nanoTime() - this.ultima$requestStartedNanos);
        }
    }

    @WrapOperation(
            method = "processChunkBuildResults",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"),
            require = 0)
    private void ultima$observeUpload(
            final RenderRegionManager regions,
            final Collection<BuilderTaskOutput> outputs,
            final UniformBufferManager uniforms,
            final Operation<Void> original) {
        long bytes = 0L;
        for (BuilderTaskOutput output : outputs) {
            bytes += Math.max(0L, output.getResultSize());
        }
        CrossPipelineBroker.uploadStarted(bytes);
        long started = System.nanoTime();
        try {
            original.call(regions, outputs, uniforms);
        } finally {
            CrossPipelineBroker.uploadCompleted(System.nanoTime() - started);
        }
    }

    @Unique
    private int ultima$deferredQueueDepth() {
        return this.taskLists == null ? 0 : this.taskLists.size();
    }

    @Unique
    private long ultima$nextDeferredAgeNanos() {
        DeferredTaskList tasks = this.taskLists;
        if (tasks == null || tasks.isEmpty()) {
            return 0L;
        }
        long encoded = tasks.firstLong();
        DeferredTaskListAccessor offsets = (DeferredTaskListAccessor)(Object)tasks;
        int localX = (int)(encoded >>> 20) & 0x3ff;
        int localY = (int)(encoded >>> 10) & 0x3ff;
        int localZ = (int)encoded & 0x3ff;
        long sectionPos = SectionPos.asLong(
                localX + offsets.ultima$getBaseOffsetX(),
                localY + net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree.SECTION_Y_MIN,
                localZ + offsets.ultima$getBaseOffsetZ());
        RenderSection section = this.renderSections.getConsistent(sectionPos);
        if (section == null || section.getPendingUpdateSince() <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.nanoTime() - section.getPendingUpdateSince());
    }
}
