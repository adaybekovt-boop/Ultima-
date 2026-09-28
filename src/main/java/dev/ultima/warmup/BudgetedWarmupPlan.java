package dev.ultima.warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Deterministic budget/cancel/fail-open state machine shared by render warmup adapters. */
public final class BudgetedWarmupPlan {
    public interface Adapter {
        String id();

        Support supports();

        int discover();

        WarmResult warm(long deadlineNanos);

        long timeoutNanos();
    }

    public record Support(boolean supported, String detail) {
    }

    /** Cooperative progress result. Incomplete work is resumed on a later pump. */
    public record WarmResult(int warmedItems, boolean complete) {
        public WarmResult {
            warmedItems = Math.max(0, warmedItems);
        }

        public static WarmResult complete(final int warmedItems) {
            return new WarmResult(warmedItems, true);
        }

        public static WarmResult pending(final int warmedItems) {
            return new WarmResult(warmedItems, false);
        }
    }

    public record AdapterResult(String id, boolean active, String state, String detail) {
    }

    public record Snapshot(
            String state,
            int completedAdapters,
            int totalAdapters,
            int discoveredItems,
            int warmedItems,
            int failures,
            long warmupNanos,
            List<AdapterResult> adapters) {
    }

    private final List<? extends Adapter> adapters;
    private final long totalBudgetNanos;
    private final LongSupplier clock;
    private final List<AdapterResult> results = new ArrayList<>();
    private State state = State.IDLE;
    private int nextAdapter;
    private int discoveredItems;
    private int warmedItems;
    private int failures;
    private long startedNanos;
    private long warmupNanos;
    private boolean adapterPrepared;
    private long adapterStartedNanos;
    private String adapterDetail = "";

    public BudgetedWarmupPlan(
            final List<? extends Adapter> adapters,
            final long totalBudgetNanos,
            final LongSupplier clock) {
        this.adapters = List.copyOf(adapters);
        this.totalBudgetNanos = Math.max(1L, totalBudgetNanos);
        this.clock = clock == null ? System::nanoTime : clock;
    }

    public void request() {
        this.state = State.PENDING;
        this.nextAdapter = 0;
        this.discoveredItems = 0;
        this.warmedItems = 0;
        this.failures = 0;
        this.startedNanos = 0L;
        this.warmupNanos = 0L;
        this.adapterPrepared = false;
        this.adapterStartedNanos = 0L;
        this.adapterDetail = "";
        this.results.clear();
    }

    public void pump(final long frameBudgetNanos) {
        if (this.state != State.PENDING && this.state != State.RUNNING) {
            return;
        }
        long now = this.clock.getAsLong();
        if (this.state == State.PENDING) {
            this.state = State.RUNNING;
            this.startedNanos = now;
        }
        long totalDeadline = saturatedAdd(this.startedNanos, this.totalBudgetNanos);
        long frameDeadline = Math.min(totalDeadline, saturatedAdd(now, Math.max(1L, frameBudgetNanos)));

        while (this.nextAdapter < this.adapters.size() && this.clock.getAsLong() < frameDeadline) {
            Adapter adapter = this.adapters.get(this.nextAdapter);
            if (!this.adapterPrepared && !prepare(adapter)) {
                continue;
            }

            long sliceStart = this.clock.getAsLong();
            try {
                long timeoutDeadline = saturatedAdd(
                        this.adapterStartedNanos, Math.max(1L, adapter.timeoutNanos()));
                long deadline = Math.min(frameDeadline, timeoutDeadline);
                WarmResult result = adapter.warm(deadline);
                long completedAt = this.clock.getAsLong();
                long duration = Math.max(0L, completedAt - sliceStart);
                this.warmupNanos += duration;
                this.warmedItems += result == null ? 0 : result.warmedItems();
                boolean complete = result != null && result.complete();
                if (completedAt > timeoutDeadline || (!complete && completedAt >= timeoutDeadline)) {
                    this.failures++;
                    this.results.add(new AdapterResult(adapter.id(), false, "timeout", this.adapterDetail));
                    advanceAdapter();
                } else if (complete) {
                    this.results.add(new AdapterResult(adapter.id(), true, "warmed", this.adapterDetail));
                    advanceAdapter();
                } else {
                    // The adapter cooperatively stopped at this frame's deadline. Resume next frame.
                    if (completedAt >= totalDeadline) {
                        this.state = State.DEFERRED;
                    }
                    return;
                }
            } catch (Throwable throwable) {
                this.warmupNanos += Math.max(0L, this.clock.getAsLong() - sliceStart);
                fail(adapter.id(), "fail_open", throwable);
                advanceAdapter();
            }
        }

        if (this.nextAdapter >= this.adapters.size()) {
            this.state = State.COMPLETE;
        } else if (this.clock.getAsLong() >= totalDeadline) {
            this.state = State.DEFERRED;
        }
    }

    public void cancel(final String reason) {
        if (this.state == State.PENDING || this.state == State.RUNNING || this.state == State.DEFERRED) {
            this.state = State.CANCELLED;
            this.results.add(new AdapterResult("plan", false, "cancelled", reason == null ? "" : reason));
        }
    }

    public boolean isPendingWork() {
        return this.state == State.PENDING || this.state == State.RUNNING;
    }

    public boolean isComplete() {
        return this.state == State.COMPLETE;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.state.name().toLowerCase(java.util.Locale.ROOT),
                this.nextAdapter,
                this.adapters.size(),
                this.discoveredItems,
                this.warmedItems,
                this.failures,
                this.warmupNanos,
                List.copyOf(this.results));
    }

    private void fail(final String id, final String state, final Throwable throwable) {
        this.failures++;
        this.results.add(new AdapterResult(id, false, state, throwable.getClass().getSimpleName()));
    }

    private boolean prepare(final Adapter adapter) {
        Support support;
        try {
            support = adapter.supports();
        } catch (Throwable throwable) {
            fail(adapter.id(), "supports_failure", throwable);
            advanceAdapter();
            return false;
        }
        if (support == null || !support.supported()) {
            this.results.add(new AdapterResult(
                    adapter.id(), false, "unsupported", support == null ? "null_support" : support.detail()));
            advanceAdapter();
            return false;
        }
        try {
            this.discoveredItems += Math.max(0, adapter.discover());
        } catch (Throwable throwable) {
            fail(adapter.id(), "discover_failure", throwable);
            advanceAdapter();
            return false;
        }
        this.adapterDetail = support.detail();
        this.adapterStartedNanos = this.clock.getAsLong();
        this.adapterPrepared = true;
        return true;
    }

    private void advanceAdapter() {
        this.nextAdapter++;
        this.adapterPrepared = false;
        this.adapterStartedNanos = 0L;
        this.adapterDetail = "";
    }

    private static long saturatedAdd(final long left, final long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private enum State {
        IDLE,
        PENDING,
        RUNNING,
        DEFERRED,
        COMPLETE,
        CANCELLED
    }
}
