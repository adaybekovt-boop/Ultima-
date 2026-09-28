package dev.ultima.mixin.cross_pipeline_admission_broker;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only decoder state for peeking the next deferred task without dequeuing it. */
@Mixin(value = DeferredTaskList.class, remap = false)
public interface DeferredTaskListAccessor {
    @Accessor("baseOffsetX")
    int ultima$getBaseOffsetX();

    @Accessor("baseOffsetZ")
    int ultima$getBaseOffsetZ();
}
