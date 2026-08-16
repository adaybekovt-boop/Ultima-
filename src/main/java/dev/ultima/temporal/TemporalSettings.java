package dev.ultima.temporal;

/**
 * Data-only settings. No graphics menu is created from this object.
 *
 * <p>Requested unsupported modes resolve to {@link TemporalMode#NATIVE} so the
 * portable path never depends on a vendor backend.
 */
public final class TemporalSettings {
    private TemporalMode requested = TemporalMode.NATIVE;

    public TemporalMode requested() {
        return this.requested;
    }

    public TemporalMode resolved() {
        return this.requested.isSupported() ? this.requested : TemporalMode.NATIVE;
    }

    public boolean requestedUnsupported() {
        return this.requested != this.resolved();
    }

    public void request(final TemporalMode mode) {
        this.requested = mode == null ? TemporalMode.NATIVE : mode;
    }

    public void requestFromKey(final String key) {
        this.request(TemporalMode.fromKey(key));
    }
}
