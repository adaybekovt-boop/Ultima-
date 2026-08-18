package dev.ultima.mixin.temporal;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.temporal.TemporalResetReason;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Shadow
    public abstract RenderTarget mainRenderTarget();

    @Shadow
    @Final
    private Camera mainCamera;

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER))
    private void ultimaTemporalBeginWorldFrame(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci,
            @Local final Matrix4f projectionMatrix) {
        CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
        Matrix4fc view = cameraState.viewRotationMatrix;
        TemporalPipeline.captureFromTarget(
                this.mainRenderTarget(),
                view,
                projectionMatrix,
                cameraState.pos.x,
                cameraState.pos.y,
                cameraState.pos.z,
                this.mainCamera.getFov());
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER))
    private void ultimaTemporalEvaluateWorld(final DeltaTracker deltaTracker, final CallbackInfo ci) {
        TemporalPipeline.get().evaluateWorld();
    }

    @Inject(method = "resize", at = @At("RETURN"))
    private void ultimaTemporalResize(final int width, final int height, final CallbackInfo ci) {
        TemporalPipeline.get().resetHistory(TemporalResetReason.FRAMEBUFFER_RESIZE);
    }
}
