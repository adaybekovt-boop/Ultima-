package dev.ultima.mixin.fsr_upscaling;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ultima.client.fsr.FsrUpscaling;
import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrResourcePlan;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private GlobalSettingsUniform globalSettingsUniform;

    @Invoker("tryTakeScreenshotIfNeeded")
    abstract void ultima$tryTakeScreenshotIfNeeded();

    @Redirect(
            method = {"render", "renderLevel"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                    opcode = Opcodes.GETFIELD))
    private RenderTarget ultimaFsrRedirectMainTarget(final GameRenderer self) {
        return FsrUpscaling.get().resolveWorldTarget(this.mainRenderTarget);
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void ultimaFsrMainRenderTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        if (FsrUpscaling.get().isWorldPass()) {
            cir.setReturnValue(FsrUpscaling.get().resolveWorldTarget(this.mainRenderTarget));
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void ultimaFsrResetWorldPass(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        // RETURN injects do not run if render() throws. Clear the redirect before
        // the vanilla resize/clear GETFIELDs so a failed frame cannot leak into the next.
        FsrUpscaling.get().abortWorldPass();
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void ultimaFsrBeginWorldPass(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        int nativeWidth = this.gameRenderState.windowRenderState.width;
        int nativeHeight = this.gameRenderState.windowRenderState.height;
        FsrResourcePlan plan = FsrUpscaling.get().beginWorldPass(nativeWidth, nativeHeight);
        this.ultimaFsrApplySkyReset();
        this.ultimaFsrSyncOutline(nativeWidth, nativeHeight);
        if (plan.redirectMainTarget()) {
            this.ultimaFsrRefreshGlobals(plan.internal().width(), plan.internal().height(), deltaTracker);
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"))
    private void ultimaFsrDeferWorldIconScreenshot(final GameRenderer self) {
        if (FsrUpscaling.get().shouldDeferWorldIconScreenshot()) {
            FsrUpscaling.get().markWorldIconScreenshotPending();
            return;
        }
        this.ultima$tryTakeScreenshotIfNeeded();
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void ultimaFsrEndWorldPass(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        int nativeWidth = this.gameRenderState.windowRenderState.width;
        int nativeHeight = this.gameRenderState.windowRenderState.height;
        boolean redirected = FsrUpscaling.get().isWorldPass();
        FsrUpscaling.get().endWorldPassAndUpscale(
                this.mainRenderTarget,
                UltimaConfig.get().fsrSettings().resolved().sharpnessStops());
        this.ultimaFsrApplySkyReset();
        if (redirected) {
            this.ultimaFsrRefreshGlobals(nativeWidth, nativeHeight, deltaTracker);
            this.ultimaFsrSyncOutline(nativeWidth, nativeHeight);
        }
        if (FsrUpscaling.get().consumeWorldIconScreenshotAfterUpscale()) {
            this.ultima$tryTakeScreenshotIfNeeded();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ultimaFsrAbortWorldPass(final DeltaTracker deltaTracker, final boolean advanceGameTime, final CallbackInfo ci) {
        FsrUpscaling.get().abortWorldPass();
    }

    @Inject(method = "resize", at = @At("RETURN"))
    private void ultimaFsrResize(final int width, final int height, final CallbackInfo ci) {
        FsrUpscaling.get().onNativeResize(width, height);
        this.ultimaFsrApplySkyReset();
        this.ultimaFsrSyncOutline(width, height);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ultimaFsrClose(final CallbackInfo ci) {
        FsrUpscaling.get().shutdown();
    }

    private void ultimaFsrApplySkyReset() {
        if (!FsrUpscaling.get().consumeSkyRendererReset()) {
            return;
        }
        this.gameRenderState.levelRenderState.shouldResetSkyRenderer = true;
        ((LevelExtractorAccessor)this.minecraft.levelExtractor).ultima$setShouldResetSkyRenderer(true);
    }

    private void ultimaFsrSyncOutline(final int nativeWidth, final int nativeHeight) {
        LevelRenderer levelRenderer = this.minecraft.levelRenderer;
        if (levelRenderer == null) {
            return;
        }
        FsrUpscaling.get().syncCompanionTarget(
                ((LevelRendererAccessor)levelRenderer).ultima$entityOutlineTarget(),
                nativeWidth,
                nativeHeight);
    }

    private void ultimaFsrRefreshGlobals(final int width, final int height, final DeltaTracker deltaTracker) {
        this.globalSettingsUniform.update(
                width,
                height,
                this.gameRenderState.optionsRenderState.glintStrength,
                this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                deltaTracker,
                this.gameRenderState.optionsRenderState.menuBackgroundBlurriness,
                this.gameRenderState.levelRenderState.cameraRenderState.pos,
                this.gameRenderState.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
    }
}
