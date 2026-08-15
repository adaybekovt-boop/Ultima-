package dev.ultima.mixin.section_task_queue;

import java.util.concurrent.locks.LockSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
public abstract class ResortTransparencyTaskMixin {
    @Redirect(method = "doTask", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;onSpinWait()V"))
    private void ultimaParkOnUploadBackpressure() {
        LockSupport.parkNanos(50_000L);
    }
}
