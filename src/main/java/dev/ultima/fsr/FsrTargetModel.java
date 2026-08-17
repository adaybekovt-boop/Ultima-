package dev.ultima.fsr;

/**
 * Allocation / resize state machine for FSR1 targets, without touching the GPU.
 * The client runtime follows the same rules: create or resize only when the
 * plan asks for a different size; release everything when the plan is inactive.
 */
public final class FsrTargetModel {
    private int worldWidth;
    private int worldHeight;
    private int easuWidth;
    private int easuHeight;
    private int outlineWidth;
    private int outlineHeight;
    private int allocations;
    private int releases;
    private int resizes;
    private boolean worldAlive;
    private boolean easuAlive;

    public void apply(final FsrResourcePlan plan) {
        if (plan == null || !plan.allocateWorldTarget()) {
            this.releaseAll();
            if (plan != null) {
                this.ensureOutline(plan.output());
            }
            return;
        }
        this.ensureWorld(plan.internal());
        if (plan.allocateEasuTarget()) {
            this.ensureEasu(plan.output());
        } else {
            this.releaseEasu();
        }
        this.ensureOutline(plan.internal());
    }

    public void onNativeResize(final FsrResourcePlan plan) {
        this.apply(plan);
    }

    public boolean hasLiveTargets() {
        return this.worldAlive || this.easuAlive;
    }

    public int liveTargetCount() {
        int count = 0;
        if (this.worldAlive) {
            count++;
        }
        if (this.easuAlive) {
            count++;
        }
        return count;
    }

    public int allocations() {
        return this.allocations;
    }

    public int releases() {
        return this.releases;
    }

    public int resizes() {
        return this.resizes;
    }

    public FsrSize worldSize() {
        return this.worldAlive ? new FsrSize(this.worldWidth, this.worldHeight) : null;
    }

    public FsrSize easuSize() {
        return this.easuAlive ? new FsrSize(this.easuWidth, this.easuHeight) : null;
    }

    public FsrSize outlineSize() {
        return new FsrSize(Math.max(1, this.outlineWidth), Math.max(1, this.outlineHeight));
    }

    public boolean isStale(final FsrResourcePlan plan) {
        if (plan == null || !plan.allocateWorldTarget()) {
            return this.hasLiveTargets();
        }
        if (!this.worldAlive || !plan.internal().equalsSize(this.worldWidth, this.worldHeight)) {
            return true;
        }
        if (plan.allocateEasuTarget()
                && (!this.easuAlive || !plan.output().equalsSize(this.easuWidth, this.easuHeight))) {
            return true;
        }
        return !plan.internal().equalsSize(this.outlineWidth, this.outlineHeight);
    }

    private void ensureWorld(final FsrSize size) {
        if (this.worldAlive && size.equalsSize(this.worldWidth, this.worldHeight)) {
            return;
        }
        if (this.worldAlive) {
            this.resizes++;
        } else {
            this.allocations++;
            this.worldAlive = true;
        }
        this.worldWidth = size.width();
        this.worldHeight = size.height();
    }

    private void ensureEasu(final FsrSize size) {
        if (this.easuAlive && size.equalsSize(this.easuWidth, this.easuHeight)) {
            return;
        }
        if (this.easuAlive) {
            this.resizes++;
        } else {
            this.allocations++;
            this.easuAlive = true;
        }
        this.easuWidth = size.width();
        this.easuHeight = size.height();
    }

    private void ensureOutline(final FsrSize size) {
        if (this.outlineWidth == size.width() && this.outlineHeight == size.height()) {
            return;
        }
        if (this.outlineWidth != 0 || this.outlineHeight != 0) {
            this.resizes++;
        }
        this.outlineWidth = size.width();
        this.outlineHeight = size.height();
    }

    private void releaseEasu() {
        if (this.easuAlive) {
            this.easuAlive = false;
            this.easuWidth = 0;
            this.easuHeight = 0;
            this.releases++;
        }
    }

    private void releaseAll() {
        if (this.worldAlive) {
            this.worldAlive = false;
            this.worldWidth = 0;
            this.worldHeight = 0;
            this.releases++;
        }
        this.releaseEasu();
    }
}
