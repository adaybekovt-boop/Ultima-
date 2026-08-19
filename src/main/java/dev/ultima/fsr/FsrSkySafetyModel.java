package dev.ultima.fsr;

/**
 * GPU-free model of the C1 SkyRenderer hazard: vanilla {@code SkyRenderer}
 * captures a {@code RenderTarget} once and calls {@code getColorTextureView()}
 * on every sky pass. Destroying Ultima's world target while that capture is
 * live is a crash. Fail-open and 1×1 deactivation must park the world target
 * and request a sky rebind to vanilla main.
 */
public final class FsrSkySafetyModel {
    public static final class TargetHandle {
        private boolean destroyed;

        public void destroy() {
            this.destroyed = true;
        }

        public boolean isDestroyed() {
            return this.destroyed;
        }

        public void use() {
            if (this.destroyed) {
                throw new IllegalStateException("closed FSR world target");
            }
        }
    }

    public static final class SkyRendererStub {
        private TargetHandle bound;

        public void bind(final TargetHandle target) {
            this.bound = target;
        }

        public TargetHandle bound() {
            return this.bound;
        }

        public void drawSkyPass() {
            if (this.bound == null) {
                return;
            }
            this.bound.use();
        }
    }

    private final TargetHandle vanillaMain = new TargetHandle();
    private TargetHandle world;
    private boolean parked;
    private boolean skyResetRequired;

    public TargetHandle vanillaMain() {
        return this.vanillaMain;
    }

    public TargetHandle world() {
        return this.world;
    }

    public boolean isParked() {
        return this.parked;
    }

    public boolean worldDestroyed() {
        return this.world != null && this.world.isDestroyed();
    }

    public TargetHandle allocateWorld() {
        this.world = new TargetHandle();
        this.parked = false;
        this.skyResetRequired = true;
        return this.world;
    }

    /**
     * C1 (a): keep the world target object alive. C1 (b): request SkyRenderer
     * to rebind to vanilla main on the next sky pass.
     */
    public void failOpenPark() {
        this.parked = true;
        this.skyResetRequired = true;
    }

    /**
     * Same park path as fail-open: 1×1 / native-equal plan must not destroy
     * a world target SkyRenderer may still hold.
     */
    public void parkForInactivePlan() {
        if (this.world != null && !this.world.isDestroyed()) {
            this.parked = true;
            this.skyResetRequired = true;
        }
    }

    /**
     * Pre-fix behaviour: destroy the world target without a sky reset.
     */
    public void destroyWorldWithoutSkyReset() {
        if (this.world != null) {
            this.world.destroy();
        }
        this.parked = false;
        this.skyResetRequired = false;
    }

    public void destroyForLifecycle() {
        if (this.world != null) {
            this.world.destroy();
        }
        this.parked = false;
        this.skyResetRequired = true;
    }

    public void unparkForActivePlan() {
        if (this.world == null || this.world.isDestroyed()) {
            this.allocateWorld();
            return;
        }
        if (this.parked) {
            this.skyResetRequired = true;
        }
        this.parked = false;
    }

    /**
     * One sky pass. If a reset was requested, rebind to the target that is
     * actually being presented (vanilla when parked / world destroyed).
     */
    public void nextSkyPass(final SkyRendererStub sky) {
        if (this.skyResetRequired) {
            boolean useVanilla = this.parked || this.world == null || this.world.isDestroyed();
            sky.bind(useVanilla ? this.vanillaMain : this.world);
            this.skyResetRequired = false;
        }
        sky.drawSkyPass();
    }
}
