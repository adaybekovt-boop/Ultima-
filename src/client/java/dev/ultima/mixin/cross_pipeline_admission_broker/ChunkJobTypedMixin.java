package dev.ultima.mixin.cross_pipeline_admission_broker;

import dev.ultima.client.broker.CrossPipelineBroker;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobTyped;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkJobTyped.class, remap = false)
public abstract class ChunkJobTypedMixin {
    @Unique private boolean ultima$observedStart;

    @Inject(
            method = "execute",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobTyped;started:Z",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER),
            require = 0)
    private void ultima$taskStarted(final ChunkBuildContext context, final CallbackInfo ci) {
        this.ultima$observedStart = true;
        CrossPipelineBroker.workerTaskStarted();
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private void ultima$taskCompleted(final ChunkBuildContext context, final CallbackInfo ci) {
        if (this.ultima$observedStart) {
            CrossPipelineBroker.workerTaskCompleted();
            this.ultima$observedStart = false;
        }
    }
}
