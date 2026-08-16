package dev.ultima.client.temporal;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ultima.temporal.MotionVectorSemantic;
import dev.ultima.temporal.TemporalMode;
import dev.ultima.temporal.TemporalResetReason;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Backend-neutral current/previous frame contract. Terrain code must not assume
 * DLSS or FSR; Native passthrough consumes the same fields and does nothing.
 */
public final class TemporalFrameData {
    public final Matrix4f currentView = new Matrix4f();
    public final Matrix4f currentProjection = new Matrix4f();
    public final Matrix4f previousView = new Matrix4f();
    public final Matrix4f previousProjection = new Matrix4f();
    public float currentJitterX;
    public float currentJitterY;
    public float previousJitterX;
    public float previousJitterY;
    public int renderWidth = 1;
    public int renderHeight = 1;
    public int outputWidth = 1;
    public int outputHeight = 1;
    public double currentCameraX;
    public double currentCameraY;
    public double currentCameraZ;
    public double previousCameraX;
    public double previousCameraY;
    public double previousCameraZ;
    public float currentFov;
    public float previousFov;
    public float exposure = 1.0F;
    public long frameIndex;
    public boolean resetHistory = true;
    public boolean resetThisFrame = true;
    public @Nullable TemporalResetReason resetReason = TemporalResetReason.FIRST_FRAME;
    public boolean hasPrevious;
    public MotionVectorSemantic motionVectorPlan = MotionVectorSemantic.UNAVAILABLE;
    public @Nullable GpuTextureView color;
    public @Nullable GpuTextureView depth;
    public @Nullable GpuTextureView motionVectors;
    public TemporalMode mode = TemporalMode.NATIVE;
    public @Nullable String backendName;
    public boolean evaluatedThisFrame;

    public void copyCurrentToPrevious() {
        this.previousView.set(this.currentView);
        this.previousProjection.set(this.currentProjection);
        this.previousJitterX = this.currentJitterX;
        this.previousJitterY = this.currentJitterY;
        this.previousCameraX = this.currentCameraX;
        this.previousCameraY = this.currentCameraY;
        this.previousCameraZ = this.currentCameraZ;
        this.previousFov = this.currentFov;
        this.hasPrevious = true;
    }

    public void setCurrentView(final Matrix4fc view) {
        this.currentView.set(view);
    }

    public void setCurrentProjection(final Matrix4fc projection) {
        this.currentProjection.set(projection);
    }

    public void invalidatePrevious(final TemporalResetReason reason) {
        this.resetHistory = true;
        this.resetThisFrame = true;
        this.resetReason = reason;
        this.hasPrevious = false;
        this.previousView.set(this.currentView);
        this.previousProjection.set(this.currentProjection);
        this.previousJitterX = this.currentJitterX;
        this.previousJitterY = this.currentJitterY;
        this.previousCameraX = this.currentCameraX;
        this.previousCameraY = this.currentCameraY;
        this.previousCameraZ = this.currentCameraZ;
        this.previousFov = this.currentFov;
        this.motionVectorPlan = MotionVectorSemantic.UNAVAILABLE;
    }
}
