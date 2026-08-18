package dev.ultima.fsr;

/**
 * GPU-free decision of what FSR1 should allocate and run for one native size.
 * When the module is off, the plan never asks for extra targets.
 */
public record FsrResourcePlan(
        boolean redirectMainTarget,
        boolean allocateWorldTarget,
        boolean allocateEasuTarget,
        boolean runUpscale,
        FsrSize internal,
        FsrSize output,
        FsrQualityPreset preset) {
    public FsrResourcePlan {
        if (internal == null) {
            internal = new FsrSize(1, 1);
        }
        if (output == null) {
            output = new FsrSize(1, 1);
        }
        if (preset == null) {
            preset = FsrQualityPreset.defaultPreset();
        }
    }

    public static FsrResourcePlan inactive(final int nativeWidth, final int nativeHeight) {
        FsrSize nativeSize = new FsrSize(nativeWidth, nativeHeight);
        return new FsrResourcePlan(false, false, false, false, nativeSize, nativeSize, FsrQualityPreset.defaultPreset());
    }

    public static FsrResourcePlan decide(
            final boolean moduleEnabled,
            final FsrQualityPreset preset,
            final int nativeWidth,
            final int nativeHeight) {
        if (!moduleEnabled) {
            return inactive(nativeWidth, nativeHeight);
        }
        FsrQualityPreset resolved = preset == null ? FsrQualityPreset.defaultPreset() : preset;
        FsrSize output = new FsrSize(nativeWidth, nativeHeight);
        FsrSize internal = FsrResolution.internal(resolved, nativeWidth, nativeHeight);
        if (internal.equals(output)) {
            return new FsrResourcePlan(false, false, false, false, internal, output, resolved);
        }
        return new FsrResourcePlan(true, true, true, true, internal, output, resolved);
    }

    /**
     * Extra Ultima-owned color targets (world + EASU ping). The vanilla entity
     * outline target is resized in place and is not counted here.
     */
    public int extraColorTargets() {
        int count = 0;
        if (this.allocateWorldTarget) {
            count++;
        }
        if (this.allocateEasuTarget) {
            count++;
        }
        return count;
    }
}
