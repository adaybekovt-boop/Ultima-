package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous GPU timestamps for the retained opaque pass. Never stalls the
 * CPU waiting for the current frame's queries.
 */
final class RetainedGpuTimers implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-retained-terrain");
    private static final int ROTATIONS = 3;
    private @Nullable GpuQueryPool pool;
    /** A query pair is readable only after both begin and end timestamps were written. */
    private final boolean[] completedRotations = new boolean[ROTATIONS];
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
            this.completedRotations[this.writeRotation] = false;
        } catch (RuntimeException ignored) {
            this.warnAndDisable("writing the retained terrain start timestamp", ignored);
        }
    }

    void endPass(final RenderPass pass) {
        if (!this.wroteThisFrame || this.pool == null) {
            return;
        }
        try {
            pass.writeTimestamp(this.pool, this.writeRotation * 2 + 1);
            this.completedRotations[this.writeRotation] = true;
        } catch (RuntimeException ignored) {
            this.warnAndDisable("writing the retained terrain end timestamp", ignored);
        }
    }

    void poll(final RetainedUploadMetrics metrics) {
        metrics.gpuTimingSupported = this.supported && this.pool != null;
        if (this.pool == null || !this.supported) {
            return;
        }
        int readRotation = (this.writeRotation + 1) % ROTATIONS;
        if (GpuQueryRotationGuard.canRead(this.completedRotations, readRotation)) {
            try {
                OptionalLong[] values = this.pool.getValues(readRotation * 2, 2);
                if (values[0].isPresent() && values[1].isPresent()) {
                    long delta = values[1].getAsLong() - values[0].getAsLong();
                    float period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
                    this.lastCompletedNs = (long)((float)delta * period);
                }
            } catch (RuntimeException ignored) {
                // Do not retry a broken pair, and never turn a query read into a HIGH error.
                this.completedRotations[readRotation] = false;
                LOGGER.warn("Skipping one retained terrain GPU timing sample: {}", ignored.toString());
            }
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
            GpuQueryPool created = RenderSystem.getDevice().createTimestampQueryPool(ROTATIONS * 2);
            if (created == null || created.size() != ROTATIONS * 2) {
                if (created != null) {
                    created.close();
                }
                LOGGER.warn("Retained terrain GPU timing is unavailable: timestamp query pool was not initialized.");
                this.supported = false;
                return false;
            }
            this.pool = created;
            return true;
        } catch (RuntimeException ignored) {
            this.warnAndDisable("creating the retained terrain timestamp query pool", ignored);
            return false;
        }
    }

    private void warnAndDisable(final String operation, final RuntimeException error) {
        if (this.supported) {
            LOGGER.warn("Retained terrain GPU timing disabled while {}: {}", operation, error.toString());
        }
        this.supported = false;
    }

    @Override
    public void close() {
        if (this.pool != null) {
            this.pool.close();
            this.pool = null;
        }
        Arrays.fill(this.completedRotations, false);
        this.writeRotation = 0;
        this.lastCompletedNs = 0L;
        this.wroteThisFrame = false;
        this.supported = true;
    }
}
