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
import dev.ultima.fsr.FsrEasuConstants;
import dev.ultima.fsr.FsrPipelineGate;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrResourcePlan;
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
    private @Nullable GpuBuffer constants;
    private boolean worldPass;
    private boolean failedOpen;
    private @Nullable FsrResourcePlan activePlan;
    private boolean evaluatedThisFrame;
    private boolean hudAfterUpscaleThisFrame;
    private boolean loggedActive;
    private boolean loggedWaitingForPipelines;

    private FsrUpscaling() {
    }

    public static FsrUpscaling get() {
        return INSTANCE;
    }

    public boolean moduleEnabled() {
        return UltimaConfig.get().isEnabled("fsr_upscaling");
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

    public RenderTarget resolveWorldTarget(final RenderTarget vanillaMain) {
        RenderTarget world = this.targets.world();
        if (this.isWorldPass() && world != null) {
            return world;
        }
        return vanillaMain;
    }

    public FsrResourcePlan beginWorldPass(final int nativeWidth, final int nativeHeight) {
        this.evaluatedThisFrame = false;
        this.hudAfterUpscaleThisFrame = false;
        if (this.failedOpen || !this.moduleEnabled()) {
            this.releaseIfInactive(nativeWidth, nativeHeight);
            this.worldPass = false;
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
            this.ensureConstants();
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
        if (this.constants != null) {
            this.constants.close();
            this.constants = null;
        }
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
        this.shutdown();
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

    private void ensureConstants() {
        if (this.constants != null) {
            return;
        }
        this.constants = RenderSystem.getDevice().createBuffer(
                () -> "Ultima FSR constants",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                CONSTANTS_BYTES);
    }

    private void dispatchEasu(final RenderTarget input, final RenderTarget output, final FsrResourcePlan plan) {
        FsrEasuConstants.EasuCon con = FsrEasuConstants.easuSameViewportAndInput(
                plan.internal().width(),
                plan.internal().height(),
                plan.output().width(),
                plan.output().height());
        this.uploadConstants(con, Float.NaN, plan.internal(), plan.output());
        this.drawPass("Ultima FSR EASU", FsrPipelines.easu(), input.getColorTextureView(), output.getColorTextureView());
    }

    private void dispatchRcas(
            final RenderTarget input,
            final RenderTarget output,
            final FsrResourcePlan plan,
            final float sharpnessStops) {
        FsrEasuConstants.RcasCon rcas = FsrEasuConstants.rcas(FsrSettings.clampSharpness(sharpnessStops));
        FsrEasuConstants.EasuCon unused = FsrEasuConstants.easuSameViewportAndInput(
                plan.output().width(),
                plan.output().height(),
                plan.output().width(),
                plan.output().height());
        this.uploadConstants(unused, rcas.sharpnessLinear(), plan.output(), plan.output());
        this.drawPass("Ultima FSR RCAS", FsrPipelines.rcas(), input.getColorTextureView(), output.getColorTextureView());
    }

    private void uploadConstants(
            final FsrEasuConstants.EasuCon con,
            final float rcasLinear,
            final FsrSize input,
            final FsrSize output) {
        this.ensureConstants();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, CONSTANTS_BYTES)
                    .putVec4(Float.isNaN(rcasLinear) ? con.con0()[0] : rcasLinear, con.con0()[1], con.con0()[2], con.con0()[3])
                    .putVec4(con.con1()[0], con.con1()[1], con.con1()[2], con.con1()[3])
                    .putVec4(con.con2()[0], con.con2()[1], con.con2()[2], con.con2()[3])
                    .putVec4(con.con3()[0], con.con3()[1], con.con3()[2], con.con3()[3])
                    .putVec2(input.width(), input.height())
                    .putVec2(output.width(), output.height())
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.constants.slice(), data);
        }
    }

    private void drawPass(
            final String label,
            final RenderPipeline pipeline,
            final GpuTextureView input,
            final GpuTextureView output) {
        if (pipeline == null || input == null || output == null || this.constants == null) {
            throw new IllegalStateException(label + " missing pipeline, texture, or constants");
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass renderPass = encoder.createRenderPass(() -> label, output, Optional.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform("FsrConstants", this.constants);
            renderPass.draw(3, 1, 0, 0);
        }
    }
}
