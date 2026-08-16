package dev.ultima.client.temporal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ultima.config.UltimaConfig;
import dev.ultima.temporal.TemporalMode;
import dev.ultima.temporal.TemporalResetReason;
import dev.ultima.temporal.TemporalSettings;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the active {@link TemporalBackend} and {@link TemporalFrameData}. Native
 * is the only backend in this build. Switching the requested mode name away
 * from Native (or onto an unsupported name) resets history.
 */
public final class TemporalPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-temporal");
    private static final TemporalPipeline INSTANCE = new TemporalPipeline();
    private static final double CAMERA_CUT_BLOCKS = 32.0;
    private static final float FOV_DISCONTINUITY_DEGREES = 5.0F;

    private final TemporalSettings settings = new TemporalSettings();
    private final TemporalFrameData frame = new TemporalFrameData();
    private TemporalBackend backend = new NativePassthroughBackend();
    private boolean initialized;
    private boolean failedOpen;
    private @Nullable String lastBackendName;
    private @Nullable String lastModeName;
    private long resetCount;
    private @Nullable TemporalResetReason lastResetReason;

    private TemporalPipeline() {
    }

    public static TemporalPipeline get() {
        return INSTANCE;
    }

    public TemporalSettings settings() {
        return this.settings;
    }

    public TemporalFrameData frame() {
        return this.frame;
    }

    public boolean isFailedOpen() {
        return this.failedOpen;
    }

    public long resetCount() {
        return this.resetCount;
    }

    public @Nullable TemporalResetReason lastResetReason() {
        return this.lastResetReason;
    }

    public void initialize() {
        if (this.initialized || this.failedOpen) {
            return;
        }
        this.applyRequestedMode();
        this.backend.initialize();
        this.initialized = true;
        LOGGER.info(
                "Ultima temporal pipeline ready: requested={} resolved={} (Native passthrough does not change pixels).",
                this.settings.requested().displayName(),
                this.settings.resolved().displayName());
        if (this.settings.requestedUnsupported()) {
            LOGGER.info(
                    "Temporal mode '{}' is not supported in this build; using Native.",
                    this.settings.requested().displayName());
        }
    }

    public void shutdown() {
        this.backend.shutdown();
        this.initialized = false;
    }

    public void failOpen(final String reason, final @Nullable Throwable error) {
        if (!this.failedOpen) {
            if (error != null) {
                LOGGER.warn("Temporal pipeline failed open (no pixel change, no upscale): {}", reason, error);
            } else {
                LOGGER.warn("Temporal pipeline failed open (no pixel change, no upscale): {}", reason);
            }
        }
        this.failedOpen = true;
    }

    public void resetHistory(final TemporalResetReason reason) {
        if (this.failedOpen) {
            return;
        }
        this.frame.invalidatePrevious(reason);
        this.backend.resetHistory();
        this.resetCount++;
        this.lastResetReason = reason;
    }

    public void beginWorldFrame(
            final Matrix4fc view,
            final Matrix4fc projection,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float fov,
            final int outputWidth,
            final int outputHeight,
            final @Nullable GpuTextureView color,
            final @Nullable GpuTextureView depth) {
        if (this.failedOpen) {
            return;
        }
        try {
            this.initialize();
            this.applyRequestedMode();
            this.detectGraphicsApiChange();
            this.detectSizeChange(outputWidth, outputHeight);

            boolean hadValidHistory = this.frame.hasPrevious && !this.frame.resetHistory;
            if (hadValidHistory) {
                this.frame.copyCurrentToPrevious();
            }

            this.frame.setCurrentView(view);
            this.frame.setCurrentProjection(projection);
            this.frame.currentCameraX = cameraX;
            this.frame.currentCameraY = cameraY;
            this.frame.currentCameraZ = cameraZ;
            this.frame.currentFov = fov;
            this.frame.outputWidth = Math.max(1, outputWidth);
            this.frame.outputHeight = Math.max(1, outputHeight);
            this.frame.color = color;
            this.frame.depth = depth;
            this.frame.evaluatedThisFrame = false;
            this.frame.exposure = 1.0F;

            if (hadValidHistory && !this.frame.resetHistory) {
                this.detectCameraCut();
                this.detectFovDiscontinuity();
            }

            if (this.frame.resetHistory || !hadValidHistory) {
                TemporalResetReason reason = this.frame.resetReason == null
                        ? TemporalResetReason.FIRST_FRAME
                        : this.frame.resetReason;
                this.frame.invalidatePrevious(reason);
            }

            this.frame.resetThisFrame = this.frame.resetHistory || !hadValidHistory;
            this.backend.beginFrame(this.frame);
            this.frame.frameIndex++;
            this.frame.hasPrevious = true;
            this.frame.resetHistory = false;
        } catch (RuntimeException e) {
            this.failOpen("beginWorldFrame", e);
        }
    }

    public void evaluateWorld() {
        if (this.failedOpen) {
            return;
        }
        try {
            this.backend.evaluate(this.frame);
        } catch (RuntimeException e) {
            this.failOpen("evaluate", e);
        }
    }

    public boolean moduleEnabled() {
        return UltimaConfig.get().isEnabled("temporal");
    }

    private void applyRequestedMode() {
        String property = System.getProperty("ultima.temporal.mode");
        if (property != null && !property.isBlank()) {
            this.settings.requestFromKey(property);
        }
        TemporalMode resolved = this.settings.resolved();
        String modeName = resolved.name();
        if (this.lastModeName != null && !this.lastModeName.equals(modeName)) {
            this.resetHistory(TemporalResetReason.BACKEND_CHANGE);
        }
        this.lastModeName = modeName;
        if (this.backend.mode() != resolved) {
            this.backend.shutdown();
            this.backend = createBackend(resolved);
            this.backend.initialize();
            this.resetHistory(TemporalResetReason.BACKEND_CHANGE);
        }
    }

    private static TemporalBackend createBackend(final TemporalMode mode) {
        if (mode.isSupported() && mode.isNative()) {
            return new NativePassthroughBackend();
        }
        return new NativePassthroughBackend();
    }

    private void detectGraphicsApiChange() {
        GpuDevice device = RenderSystem.tryGetDevice();
        String name = device == null ? null : device.getDeviceInfo().backendName();
        if (this.lastBackendName != null && name != null && !this.lastBackendName.equals(name)) {
            this.resetHistory(TemporalResetReason.GRAPHICS_API_SWITCH);
        }
        if (name != null) {
            this.lastBackendName = name;
            this.frame.backendName = name;
        }
    }

    private void detectSizeChange(final int outputWidth, final int outputHeight) {
        if (this.frame.outputWidth != outputWidth || this.frame.outputHeight != outputHeight) {
            if (this.frame.frameIndex > 0) {
                this.resetHistory(TemporalResetReason.FRAMEBUFFER_RESIZE);
            }
        }
        var recommended = this.backend.getRecommendedRenderSize(outputWidth, outputHeight);
        if (this.frame.renderWidth != recommended.width() || this.frame.renderHeight != recommended.height()) {
            if (this.frame.frameIndex > 0 && (this.frame.renderWidth > 1 || this.frame.renderHeight > 1)) {
                this.resetHistory(TemporalResetReason.RENDER_SCALE_CHANGE);
            }
        }
    }

    private void detectCameraCut() {
        double dx = this.frame.currentCameraX - this.frame.previousCameraX;
        double dy = this.frame.currentCameraY - this.frame.previousCameraY;
        double dz = this.frame.currentCameraZ - this.frame.previousCameraZ;
        if (dx * dx + dy * dy + dz * dz > CAMERA_CUT_BLOCKS * CAMERA_CUT_BLOCKS) {
            this.resetHistory(TemporalResetReason.CAMERA_CUT);
        }
    }

    private void detectFovDiscontinuity() {
        if (Math.abs(this.frame.currentFov - this.frame.previousFov) > FOV_DISCONTINUITY_DEGREES) {
            this.resetHistory(TemporalResetReason.FOV_DISCONTINUITY);
        }
    }

    public static void captureFromTarget(
            final RenderTarget target,
            final Matrix4fc view,
            final Matrix4fc projection,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float fov) {
        get().beginWorldFrame(
                view,
                projection,
                cameraX,
                cameraY,
                cameraZ,
                fov,
                target.width,
                target.height,
                target.getColorTextureView(),
                target.getDepthTextureView());
    }
}
