package dev.ultima.client.fsr;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.fsr.FsrResourcePlan;
import dev.ultima.fsr.FsrSize;
import dev.ultima.fsr.FsrTargetModel;
import org.jspecify.annotations.Nullable;

/**
 * Ultima-owned FSR1 color targets. The world target is parked (kept alive)
 * when a session plan goes inactive so {@code SkyRenderer}'s captured
 * reference cannot dangle. Destroy happens only on {@link #close()}.
 */
final class FsrTargets implements AutoCloseable {
    private final FsrTargetModel model = new FsrTargetModel();
    private @Nullable TextureTarget world;
    private @Nullable TextureTarget easu;

    FsrTargetModel model() {
        return this.model;
    }

    void apply(final FsrResourcePlan plan) {
        RenderSystem.assertOnRenderThread();
        this.model.apply(plan);
        if (!plan.allocateWorldTarget()) {
            this.releaseEasu();
            return;
        }
        this.world = ensureTarget(this.world, "Ultima FSR world", plan.internal(), true);
        if (plan.allocateEasuTarget()) {
            this.easu = ensureTarget(this.easu, "Ultima FSR EASU", plan.output(), false);
        } else {
            this.releaseEasu();
        }
    }

    @Nullable TextureTarget world() {
        return this.world;
    }

    @Nullable TextureTarget easu() {
        return this.easu;
    }

    boolean hasLiveTargets() {
        return this.world != null || this.easu != null;
    }

    boolean hasLiveWorldTarget() {
        return this.world != null;
    }

    private void releaseEasu() {
        if (this.easu != null) {
            this.easu.destroyBuffers();
            this.easu = null;
        }
    }

    private static TextureTarget ensureTarget(
            final @Nullable TextureTarget existing,
            final String label,
            final FsrSize size,
            final boolean useDepth) {
        if (existing != null && existing.width == size.width() && existing.height == size.height()) {
            return existing;
        }
        if (existing != null) {
            existing.resize(size.width(), size.height());
            return existing;
        }
        return new TextureTarget(label, size.width(), size.height(), useDepth, GpuFormat.RGBA8_UNORM);
    }

    static void resizeIfPresent(final @Nullable RenderTarget target, final FsrSize size) {
        if (target == null || (target.width == size.width() && target.height == size.height())) {
            return;
        }
        target.resize(size.width(), size.height());
    }

    @Override
    public void close() {
        if (this.world != null) {
            this.world.destroyBuffers();
            this.world = null;
        }
        this.releaseEasu();
        this.model.destroyForLifecycle();
    }
}
