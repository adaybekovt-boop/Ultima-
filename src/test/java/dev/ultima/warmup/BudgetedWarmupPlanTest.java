package dev.ultima.warmup;

import java.util.List;

/** Warmup safety contracts: budgets, continuation, cancellation, timeout, and fail-open. */
public final class BudgetedWarmupPlanTest {
    private BudgetedWarmupPlanTest() {
    }

    public static void main(final String[] args) {
        incompleteAdapterResumesWithoutRediscovery();
        unsupportedAdapterDoesNotRun();
        adapterFailureDoesNotBlockFollowingWork();
        timeoutFailsOpen();
        totalBudgetDefersRemainingWork();
        cancellationStopsWork();
        adapterCanRestoreState();
    }

    private static void incompleteAdapterResumesWithoutRediscovery() {
        Clock clock = new Clock();
        IncrementalAdapter adapter = new IncrementalAdapter("incremental", clock, 3, 4L, 100L);
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(adapter), 100L, clock::now);
        plan.request();
        plan.pump(5L);
        require(plan.snapshot().state().equals("running"), "partial work did not remain runnable");
        plan.pump(5L);
        plan.pump(5L);
        BudgetedWarmupPlan.Snapshot snapshot = plan.snapshot();
        require(snapshot.state().equals("complete"), "continued adapter did not complete");
        require(snapshot.discoveredItems() == 3, "adapter was discovered more than once");
        require(snapshot.warmedItems() == 3, "incremental progress was lost");
        require(adapter.discoveries == 1, "discover was called more than once");
    }

    private static void unsupportedAdapterDoesNotRun() {
        Clock clock = new Clock();
        FakeAdapter adapter = new FakeAdapter("unsupported", clock);
        adapter.supported = false;
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(adapter), 100L, clock::now);
        plan.request();
        plan.pump(10L);
        require(adapter.warmCalls == 0, "unsupported adapter ran");
        require(plan.snapshot().adapters().getFirst().state().equals("unsupported"), "unsupported state missing");
    }

    private static void adapterFailureDoesNotBlockFollowingWork() {
        Clock clock = new Clock();
        FakeAdapter broken = new FakeAdapter("broken", clock);
        broken.throwOnWarm = true;
        FakeAdapter healthy = new FakeAdapter("healthy", clock);
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(broken, healthy), 100L, clock::now);
        plan.request();
        plan.pump(50L);
        require(plan.snapshot().state().equals("complete"), "failure blocked following adapter");
        require(plan.snapshot().failures() == 1, "failure was not counted");
        require(healthy.warmCalls == 1, "following adapter did not run");
    }

    private static void timeoutFailsOpen() {
        Clock clock = new Clock();
        FakeAdapter slow = new FakeAdapter("slow", clock);
        slow.timeout = 3L;
        slow.advancePerWarm = 4L;
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(slow), 100L, clock::now);
        plan.request();
        plan.pump(50L);
        require(plan.snapshot().state().equals("complete"), "timed-out adapter held the plan");
        require(plan.snapshot().failures() == 1, "timeout was not counted");
        require(plan.snapshot().adapters().getFirst().state().equals("timeout"), "timeout state missing");
    }

    private static void totalBudgetDefersRemainingWork() {
        Clock clock = new Clock();
        IncrementalAdapter adapter = new IncrementalAdapter("bounded", clock, 10, 2L, 100L);
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(adapter), 5L, clock::now);
        plan.request();
        plan.pump(20L);
        plan.pump(20L);
        plan.pump(20L);
        require(plan.snapshot().state().equals("deferred"), "total budget did not defer remaining work");
        int calls = adapter.warmCalls;
        plan.pump(100L);
        require(adapter.warmCalls == calls, "deferred plan continued without a new request");
    }

    private static void cancellationStopsWork() {
        Clock clock = new Clock();
        FakeAdapter adapter = new FakeAdapter("cancel", clock);
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(adapter), 100L, clock::now);
        plan.request();
        plan.cancel("disconnect");
        plan.pump(100L);
        require(adapter.warmCalls == 0, "cancelled work ran");
        require(plan.snapshot().state().equals("cancelled"), "cancel state missing");
    }

    private static void adapterCanRestoreState() {
        Clock clock = new Clock();
        int[] state = {7};
        FakeAdapter adapter = new FakeAdapter("restore", clock) {
            @Override
            public BudgetedWarmupPlan.WarmResult warm(final long deadlineNanos) {
                int original = state[0];
                try {
                    state[0] = 99;
                    return BudgetedWarmupPlan.WarmResult.complete(1);
                } finally {
                    state[0] = original;
                }
            }
        };
        BudgetedWarmupPlan plan = new BudgetedWarmupPlan(List.of(adapter), 100L, clock::now);
        plan.request();
        plan.pump(100L);
        require(state[0] == 7, "adapter did not restore state");
    }

    private static class FakeAdapter implements BudgetedWarmupPlan.Adapter {
        final String id;
        final Clock clock;
        boolean supported = true;
        boolean throwOnWarm;
        long timeout = 100L;
        long advancePerWarm;
        int warmCalls;
        int discoveries;

        FakeAdapter(final String id, final Clock clock) {
            this.id = id;
            this.clock = clock;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public BudgetedWarmupPlan.Support supports() {
            return new BudgetedWarmupPlan.Support(this.supported, this.supported ? "test" : "off");
        }

        @Override
        public int discover() {
            this.discoveries++;
            return 1;
        }

        @Override
        public BudgetedWarmupPlan.WarmResult warm(final long deadlineNanos) {
            this.warmCalls++;
            this.clock.advance(this.advancePerWarm);
            if (this.throwOnWarm) {
                throw new IllegalStateException("injected");
            }
            return BudgetedWarmupPlan.WarmResult.complete(1);
        }

        @Override
        public long timeoutNanos() {
            return this.timeout;
        }
    }

    private static final class IncrementalAdapter extends FakeAdapter {
        private final int target;
        private final long workNanos;
        private int completed;

        IncrementalAdapter(
                final String id,
                final Clock clock,
                final int target,
                final long workNanos,
                final long timeout) {
            super(id, clock);
            this.target = target;
            this.workNanos = workNanos;
            this.timeout = timeout;
        }

        @Override
        public int discover() {
            this.discoveries++;
            return this.target;
        }

        @Override
        public BudgetedWarmupPlan.WarmResult warm(final long deadlineNanos) {
            this.warmCalls++;
            if (this.completed >= this.target) {
                return BudgetedWarmupPlan.WarmResult.complete(0);
            }
            this.clock.advance(this.workNanos);
            this.completed++;
            return new BudgetedWarmupPlan.WarmResult(1, this.completed >= this.target);
        }
    }

    private static final class Clock {
        private long nanos;

        long now() {
            return this.nanos;
        }

        void advance(final long delta) {
            this.nanos += Math.max(0L, delta);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
