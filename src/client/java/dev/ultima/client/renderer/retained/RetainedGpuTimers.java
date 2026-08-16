package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous GPU timestamps for the retained opaque pass. Never stalls the
 * CPU waiting for the current frame's queries.
 */
final class RetainedGpuTimers implements AutoCloseable {
    private static final int ROTATIONS = 3;
    private @Nullable GpuQueryPool pool;
    private int writeRotation;
    private long lastCompletedNs;
    private boolean supported = true;
    private boolean wroteThisFrame;

    void beginPass(final RenderPass pass) {
        this.wroteThisFrame = false;
        if (!this.ensurePool()) {
            return;
        }
        try {
            pass.writeTimestamp(this.pool, this.writeRotation * 2);
            this.wroteThisFrame = true;
        } catch (RuntimeException ignored) {
            this.supported = false;
        }
    }

    void endPass(final RenderPass pass) {
        if (!this.wroteThisFrame || this.pool == null) {
            return;
        }
        try {
            pass.writeTimestamp(this.pool, this.writeRotation * 2 + 1);
        } catch (RuntimeException ignored) {
            this.supported = false;
        }
    }

    void poll(final RetainedUploadMetrics metrics) {
        metrics.gpuTimingSupported = this.supported && this.pool != null;
        if (this.pool == null || !this.supported) {
            return;
        }
        int readRotation = (this.writeRotation + 1) % ROTATIONS;
        try {
            OptionalLong[] values = this.pool.getValues(readRotation * 2, 2);
            if (values[0].isPresent() && values[1].isPresent()) {
                long delta = values[1].getAsLong() - values[0].getAsLong();
                float period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
                this.lastCompletedNs = (long)((float)delta * period);
            }
        } catch (RuntimeException ignored) {
            this.supported = false;
        }
        if (this.wroteThisFrame) {
            this.writeRotation = (this.writeRotation + 1) % ROTATIONS;
            this.wroteThisFrame = false;
        }
        metrics.gpuTerrainNs = this.lastCompletedNs;
    }

    private boolean ensurePool() {
        if (!this.supported) {
            return false;
        }
        if (this.pool != null) {
            return true;
        }
        try {
            this.pool = RenderSystem.getDevice().createTimestampQueryPool(ROTATIONS * 2);
            return this.pool != null;
        } catch (RuntimeException ignored) {
            this.supported = false;
            return false;
        }
    }

    @Override
    public void close() {
        if (this.pool != null) {
            this.pool.close();
            this.pool = null;
        }
    }
}
