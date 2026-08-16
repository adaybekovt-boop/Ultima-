package dev.ultima.mixin.section_task_queue;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface SectionTaskAccessor {
    @Accessor("isCancelled")
    AtomicBoolean ultima$isCancelled();
}
