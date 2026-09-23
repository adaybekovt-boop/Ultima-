package dev.ultima.client.fsr;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import dev.ultima.fsr.FsrEasuConstants;
import dev.ultima.fsr.FsrIrisCapabilities;
import dev.ultima.fsr.FsrRuntimeGate;
import dev.ultima.fsr.FsrPipelineGate;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrResourcePlan;
import dev.ultima.fsr.FsrScreenshotPolicy;
import dev.ultima.fsr.FsrSettings;
import dev.ultima.fsr.FsrSize;
import dev.ultima.fsr.FsrTargetModel;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client runtime for FSR1. Allocates nothing until the module is enabled and a
 * world pass actually needs an internal size different from native.
 */
public final class FsrUpscaling {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-fsr");
    private static final int MODULE_INDEX = UltimaModules.indexOf("fsr_upscaling");
    private static final FsrUpscaling INSTANCE = new FsrUpscaling();
    static final int CONSTANTS_BYTES = new Std140SizeCalculator()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec2()
            .putVec2()
            .get();

    private final FsrTargets targets = new FsrTargets();
    private @Nullable GpuBuffer easuConstants;
    private @Nullable GpuBuffer rcasConstants;
    private int cachedEasuInW = -1;
    private int cachedEasuInH = -1;
    private int cachedEasuOutW = -1;
    private int cachedEasuOutH = -1;
    private int cachedRcasW = -1;
    private int cachedRcasH = -1;
    private int cachedRcasSharpnessBits;
    private boolean worldPass;
    private boolean failedOpen;
    private @Nullable FsrResourcePlan activePlan;
    private boolean evaluatedThisFrame;
    private boolean hudAfterUpscaleThisFrame;
    private boolean loggedActive;
    private boolean loggedWaitingForPipelines;
    private boolean worldIconScreenshotPending;

    private FsrUpscaling() {
    }

    public static FsrUpscaling get() {
        return INSTANCE;
    }

    public boolean moduleEnabled() {
        return UltimaConfig.get().isRuntimeEnabled(MODULE_INDEX);
    }

    public boolean isFailedOpen() {
        return this.failedOpen;
    }

    public boolean isWorldPass() {
        return this.worldPass && this.targets.world() != null && !this.failedOpen;
    }

    public boolean evaluatedThisFrame() {
        return this.evaluatedThisFrame;
    }

    public boolean hudCompositesAfterUpscale() {
        return this.hudAfterUpscaleThisFrame;
    }

    public @Nullable FsrResourcePlan activePlan() {
        return this.activePlan;
    }

    public FsrTargetModel targetModel() {
        return this.targets.model();
    }

    public boolean hasLiveTargets() {
        return this.targets.hasLiveTargets();
    }

    public boolean hasLiveWorldTarget() {
        return this.targets.hasLiveWorldTarget();
    }

    /**
     * Apply a pending C1 sky rebind: set both the current-frame
     * {@code LevelRenderState} flag (extract already ran) and the extractor
     * flag (survives the next extract).
     */
    public boolean consumeSkyRendererReset() {
        return this.targets.model().consumeSkyResetRequired();
    }

    public boolean shouldDeferWorldIconScreenshot() {
        return FsrScreenshotPolicy.onVanillaScreenshotCall(this.isWorldPass())
                == FsrScreenshotPolicy.Action.DEFER_UNTIL_AFTER_RCAS;
    }

    public void markWorldIconScreenshotPending() {
        this.worldIconScreenshotPending = true;
    }

    public boolean consumeWorldIconScreenshotAfterUpscale() {
        boolean capture = FsrScreenshotPolicy.captureAfterRcas(this.worldIconScreenshotPending, this.evaluatedThisFrame);
        this.worldIconScreenshotPending = false;
        return capture;
    }

    public RenderTarget resolveWorldTarget(final RenderTarget vanillaMain) {
        RenderTarget world = this.targets.world();
        if (this.isWorldPass() && world != null) {
            return world;
        }
        return vanillaMain;
    }

    /**
     * Plan this frame's world-pass redirect.
     *
     * <p>Iris protection is <em>not</em> this method. When Iris is loaded,
     * {@link dev.ultima.fsr.FsrCompatibility#blocks(String)} disables
     * {@code fsr_upscaling}, and {@link dev.ultima.config.UltimaMixinPlugin}
     * never applies {@code GameRendererMixin}, so this method is not called.
     * {@link FsrRuntimeGate} is a last-line refuse for tests or a mixin that
     * was already applied; it is unreachable for a live Iris load.
     */
    public FsrResourcePlan beginWorldPass(final int nativeWidth, final int nativeHeight) {
        this.evaluatedThisFrame = false;
        this.hudAfterUpscaleThisFrame = false;
        boolean enabled = this.moduleEnabled();
        boolean irisLoaded = FsrIrisCapabilities.isIrisModLoaded();
        if (!FsrRuntimeGate.allowWorldTargetHijack(enabled, this.failedOpen, irisLoaded)) {
            if (irisLoaded && enabled && !this.failedOpen) {
                this.failOpen(
                        "Iris is loaded without a safe post-Iris FSR hook; leaving Iris rendering untouched",
                        null);
            } else {
                this.releaseIfInactive(nativeWidth, nativeHeight);
                this.worldPass = false;
            }
            this.activePlan = FsrResourcePlan.inactive(nativeWidth, nativeHeight);
            return this.activePlan;
        }
        try {
            FsrSettings settings = UltimaConfig.get().fsrSettings().resolved();
            FsrQualityPreset preset = settings.preset();
            FsrResourcePlan plan = FsrResourcePlan.decide(true, preset, nativeWidth, nativeHeight);
            if (!plan.runUpscale()) {
                this.targets.apply(plan);
                this.worldPass = false;
                this.activePlan = plan;
                return plan;
            }
            boolean compiled = FsrPipelines.ensureCompiled();
            FsrPipelineGate.Action gate = FsrPipelineGate.decide(compiled, FsrPipelines.compileFailed());
            if (gate == FsrPipelineGate.Action.FAIL_OPEN) {
                this.failOpen("FSR1 pipelines failed to compile", null);
                this.activePlan = FsrResourcePlan.inactive(nativeWidth, nativeHeight);
                return this.activePlan;
            }
            if (gate == FsrPipelineGate.Action.WAIT_FOR_PIPELINES) {
                if (!this.loggedWaitingForPipelines) {
                    LOGGER.info("FSR1 is waiting for shader sources or the GPU device; this frame stays native.");
                    this.loggedWaitingForPipelines = true;
                }
                this.targets.apply(FsrResourcePlan.inactive(nativeWidth, nativeHeight));
                this.worldPass = false;
                this.activePlan = FsrResourcePlan.inactive(nativeWidth, nativeHeight);
                return this.activePlan;
            }
            this.targets.apply(plan);
            this.ensureConstantBuffers();
            this.worldPass = this.targets.world() != null;
            this.activePlan = plan;
            return plan;
        } catch (RuntimeException e) {
            this.failOpen("beginWorldPass", e);
            this.activePlan = FsrResourcePlan.inactive(nativeWidth, nativeHeight);
            return this.activePlan;
        }
    }

    public void endWorldPassAndUpscale(final RenderTarget vanillaMain, final float sharpnessStops) {
        if (!this.worldPass) {
            return;
        }
        try {
            FsrResourcePlan plan = this.activePlan;
            RenderTarget world = this.targets.world();
            RenderTarget easu = this.targets.easu();
            if (plan == null || !plan.runUpscale() || world == null || easu == null || vanillaMain == null) {
                this.failOpen("evaluate missing FSR targets after a redirected world pass", null);
                return;
            }
            if (!FsrPipelines.ensureCompiled()) {
                this.failOpen("FSR1 pipelines became invalid", null);
                return;
            }
            this.dispatchEasu(world, easu, plan);
            this.dispatchRcas(easu, vanillaMain, plan, sharpnessStops);
            this.evaluatedThisFrame = true;
            this.hudAfterUpscaleThisFrame = true;
            if (!this.loggedActive) {
                LOGGER.info(
                        "FSR1 EASU+RCAS active: {}x{} -> {}x{} preset={}",
                        plan.internal().width(),
                        plan.internal().height(),
                        plan.output().width(),
                        plan.output().height(),
                        plan.preset().name().toLowerCase(Locale.ROOT));
                this.loggedActive = true;
            }
        } catch (RuntimeException e) {
            this.failOpen("evaluate", e);
        } finally {
            this.worldPass = false;
        }
    }

    public void abortWorldPass() {
        this.worldPass = false;
    }

    public void onNativeResize(final int nativeWidth, final int nativeHeight) {
        if (this.failedOpen || !this.moduleEnabled()) {
            this.releaseIfInactive(nativeWidth, nativeHeight);
            return;
        }
        FsrResourcePlan plan = FsrResourcePlan.decide(true, UltimaConfig.get().fsrSettings().resolved().preset(), nativeWidth, nativeHeight);
        this.targets.apply(plan);
        this.activePlan = plan;
    }

    public void shutdown() {
        this.worldPass = false;
        this.targets.close();
        this.releaseConstants();
        this.activePlan = null;
    }

    public void failOpen(final String reason, final @Nullable Throwable error) {
        if (!this.failedOpen) {
            if (error != null) {
                LOGGER.warn("FSR1 failed open (vanilla native resolution, no upscale): {}", reason, error);
            } else {
                LOGGER.warn("FSR1 failed open (vanilla native resolution, no upscale): {}", reason);
            }
        }
        this.failedOpen = true;
        this.worldPass = false;
        this.loggedActive = false;
        this.worldIconScreenshotPending = false;
        FsrResourcePlan parked = FsrResourcePlan.inactive(
                this.activePlan == null ? 1 : this.activePlan.output().width(),
                this.activePlan == null ? 1 : this.activePlan.output().height());
        this.targets.apply(parked);
        this.activePlan = parked;
        this.releaseConstants();
    }

    private void releaseIfInactive(final int nativeWidth, final int nativeHeight) {
        FsrResourcePlan inactive = FsrResourcePlan.inactive(nativeWidth, nativeHeight);
        this.targets.apply(inactive);
        this.activePlan = inactive;
    }

    public FsrSize companionTargetSize(final int nativeWidth, final int nativeHeight) {
        if (this.failedOpen || !this.moduleEnabled() || this.activePlan == null || !this.activePlan.allocateWorldTarget()) {
            return new FsrSize(nativeWidth, nativeHeight);
        }
        return this.activePlan.internal();
    }

    public void syncCompanionTarget(final @Nullable RenderTarget outline, final int nativeWidth, final int nativeHeight) {
        if (outline == null) {
            return;
        }
        FsrTargets.resizeIfPresent(outline, this.companionTargetSize(nativeWidth, nativeHeight));
    }

    private void ensureConstantBuffers() {
        if (this.easuConstants == null) {
            this.easuConstants = RenderSystem.getDevice().createBuffer(
                    () -> "Ultima FSR EASU constants",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    CONSTANTS_BYTES);
            this.cachedEasuInW = -1;
        }
        if (this.rcasConstants == null) {
            this.rcasConstants = RenderSystem.getDevice().createBuffer(
                    () -> "Ultima FSR RCAS constants",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    CONSTANTS_BYTES);
            this.cachedRcasW = -1;
        }
    }

    private void releaseConstants() {
        if (this.easuConstants != null) {
            this.easuConstants.close();
            this.easuConstants = null;
        }
        if (this.rcasConstants != null) {
            this.rcasConstants.close();
            this.rcasConstants = null;
        }
        this.cachedEasuInW = -1;
        this.cachedEasuInH = -1;
        this.cachedEasuOutW = -1;
        this.cachedEasuOutH = -1;
        this.cachedRcasW = -1;
        this.cachedRcasH = -1;
        this.cachedRcasSharpnessBits = 0;
    }

    private void dispatchEasu(final RenderTarget input, final RenderTarget output, final FsrResourcePlan plan) {
        this.ensureConstantBuffers();
        int inW = plan.internal().width();
        int inH = plan.internal().height();
        int outW = plan.output().width();
        int outH = plan.output().height();
        boolean same = this.cachedEasuInW == inW
                && this.cachedEasuInH == inH
                && this.cachedEasuOutW == outW
                && this.cachedEasuOutH == outH;
        ConstantWriter upload = null;
        if (!same) {
            FsrEasuConstants.EasuCon con = FsrEasuConstants.easuSameViewportAndInput(inW, inH, outW, outH);
            upload = (encoder, buffer) -> this.writeConstants(encoder, buffer, con, Float.NaN, plan.internal(), plan.output());
        }
        this.drawPass(
                "Ultima FSR EASU",
                FsrPipelines.easu(),
                input.getColorTextureView(),
                output.getColorTextureView(),
                this.easuConstants,
                upload);
        this.cachedEasuInW = inW;
        this.cachedEasuInH = inH;
        this.cachedEasuOutW = outW;
        this.cachedEasuOutH = outH;
    }

    private void dispatchRcas(
            final RenderTarget input,
            final RenderTarget output,
            final FsrResourcePlan plan,
            final float sharpnessStops) {
        this.ensureConstantBuffers();
        float clamped = FsrSettings.clampSharpness(sharpnessStops);
        int bits = Float.floatToIntBits(clamped);
        int width = plan.output().width();
        int height = plan.output().height();
        boolean same = this.cachedRcasW == width && this.cachedRcasH == height && this.cachedRcasSharpnessBits == bits;
        ConstantWriter upload = null;
        if (!same) {
            FsrEasuConstants.RcasCon rcas = FsrEasuConstants.rcas(clamped);
            FsrEasuConstants.EasuCon layout = FsrEasuConstants.easuSameViewportAndInput(width, height, width, height);
            upload = (encoder, buffer) -> this.writeConstants(
                    encoder, buffer, layout, rcas.sharpnessLinear(), plan.output(), plan.output());
        }
        this.drawPass(
                "Ultima FSR RCAS",
                FsrPipelines.rcas(),
                input.getColorTextureView(),
                output.getColorTextureView(),
                this.rcasConstants,
                upload);
        this.cachedRcasW = width;
        this.cachedRcasH = height;
        this.cachedRcasSharpnessBits = bits;
    }

    private void writeConstants(
            final CommandEncoder encoder,
            final GpuBuffer constants,
            final FsrEasuConstants.EasuCon con,
            final float rcasLinear,
            final FsrSize input,
            final FsrSize output) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, CONSTANTS_BYTES)
                    .putVec4(Float.isNaN(rcasLinear) ? con.con0()[0] : rcasLinear, con.con0()[1], con.con0()[2], con.con0()[3])
                    .putVec4(con.con1()[0], con.con1()[1], con.con1()[2], con.con1()[3])
                    .putVec4(con.con2()[0], con.con2()[1], con.con2()[2], con.con2()[3])
                    .putVec4(con.con3()[0], con.con3()[1], con.con3()[2], con.con3()[3])
                    .putVec2(input.width(), input.height())
                    .putVec2(output.width(), output.height())
                    .get();
            encoder.writeToBuffer(constants.slice(), data);
        }
    }

    private void drawPass(
            final String label,
            final RenderPipeline pipeline,
            final GpuTextureView input,
            final GpuTextureView output,
            final @Nullable GpuBuffer constants,
            final @Nullable ConstantWriter upload) {
        if (pipeline == null || input == null || output == null || constants == null) {
            throw new IllegalStateException(label + " missing pipeline, texture, or constants");
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (upload != null) {
            upload.write(encoder, constants);
        }
        try (RenderPass renderPass = encoder.createRenderPass(() -> label, output, Optional.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform("FsrConstants", constants);
            renderPass.draw(3, 1, 0, 0);
        }
    }

    @FunctionalInterface
    private interface ConstantWriter {
        void write(CommandEncoder encoder, GpuBuffer constants);
    }
}
