package dev.ultima.mixin.section_task_queue;

import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same nearest-initial / recompile-quota policy as vanilla. Cancelled tasks are
 * compacted in one pass instead of {@code ListIterator.remove} shifting the
 * array for every cancelled entry.
 */
@Mixin(SectionTaskDynamicQueue.class)
public abstract class SectionTaskDynamicQueueMixin {
    @Shadow
    private int recompileQuota;

    @Shadow
    @Final
    private List<SectionRenderDispatcher.RenderSection.SectionTask> tasks;

    @Shadow
    private SectionRenderDispatcher.RenderSection.@Nullable SectionTask removeTaskByIndex(int taskIndex) {
        throw new AssertionError();
    }

    @Inject(method = "poll", at = @At("HEAD"), cancellable = true)
    private void ultimaCompactPoll(final Vec3 cameraPos, final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir) {
        synchronized (this) {
            this.ultima$compactCancelled();
            int bestInitialCompileTaskIndex = -1;
            int bestRecompileTaskIndex = -1;
            double bestInitialCompileDistance = Double.MAX_VALUE;
            double bestRecompileDistance = Double.MAX_VALUE;
            int size = this.tasks.size();
            for (int taskIndex = 0; taskIndex < size; taskIndex++) {
                SectionRenderDispatcher.RenderSection.SectionTask task = this.tasks.get(taskIndex);
                double distance = task.getRenderOrigin().distToCenterSqr(cameraPos);
                if (!task.isRecompile() && distance < bestInitialCompileDistance) {
                    bestInitialCompileDistance = distance;
                    bestInitialCompileTaskIndex = taskIndex;
                }
                if (task.isRecompile() && distance < bestRecompileDistance) {
                    bestRecompileDistance = distance;
                    bestRecompileTaskIndex = taskIndex;
                }
            }

            boolean hasRecompileTask = bestRecompileTaskIndex >= 0;
            boolean hasInitialCompileTask = bestInitialCompileTaskIndex >= 0;
            if (!hasRecompileTask || hasInitialCompileTask && (this.recompileQuota <= 0 || !(bestRecompileDistance < bestInitialCompileDistance))) {
                this.recompileQuota = 2;
                cir.setReturnValue(this.removeTaskByIndex(bestInitialCompileTaskIndex));
            } else {
                this.recompileQuota--;
                cir.setReturnValue(this.removeTaskByIndex(bestRecompileTaskIndex));
            }
        }
    }

    @Unique
    private void ultima$compactCancelled() {
        int write = 0;
        int size = this.tasks.size();
        for (int i = 0; i < size; i++) {
            SectionRenderDispatcher.RenderSection.SectionTask task = this.tasks.get(i);
            if (!((SectionTaskAccessor)task).ultima$isCancelled().get()) {
                if (write != i) {
                    this.tasks.set(write, task);
                }
                write++;
            }
        }
        if (write == size) {
            return;
        }
        for (int i = size - 1; i >= write; i--) {
            this.tasks.remove(i);
        }
    }
}
