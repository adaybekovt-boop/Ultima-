package dev.ultima.client.benchmark;

/** Pure contract checks for FPS-independent benchmark replay. */
public final class ReplayTimelineTest {
    private ReplayTimelineTest() {
    }

    public static void main(final String[] args) {
        tickRouteDoesNotDependOnFramesPerTick();
        tickDurationHasStableBoundary();
        legacyFrameModeRemainsFrameIndexed();
        sampleBufferGrowsWithoutLosingSamples();
        shaderReloadMetricsAccumulateCompleteBoundary();
    }

    private static void tickRouteDoesNotDependOnFramesPerTick() {
        ReplayTimeline slow = new ReplayTimeline(ReplayTimeline.Mode.TICK, 2, 5);
        ReplayTimeline fast = new ReplayTimeline(ReplayTimeline.Mode.TICK, 2, 5);
        long slowAtTickFour = -1L;
        long fastAtTickFour = -1L;
        for (long tick = 100; tick <= 104; tick++) {
            slowAtTickFour = slow.advance(tick).routeUnit();
            for (int frame = 0; frame < 8; frame++) {
                fastAtTickFour = fast.advance(tick).routeUnit();
            }
        }
        require(slowAtTickFour == 4L, "slow route unit");
        require(fastAtTickFour == slowAtTickFour, "tick route changed with FPS");
    }

    private static void tickDurationHasStableBoundary() {
        ReplayTimeline timeline = new ReplayTimeline(ReplayTimeline.Mode.TICK, 2, 3);
        require(timeline.advance(40).warmup(), "tick 0 must warm up");
        require(timeline.advance(41).warmup(), "tick 1 must warm up");
        require(timeline.advance(42).sampling(), "tick 2 must sample");
        require(timeline.advance(44).sampling(), "last sample tick must sample");
        require(timeline.advance(45).complete(), "end boundary must complete");
    }

    private static void legacyFrameModeRemainsFrameIndexed() {
        ReplayTimeline timeline = new ReplayTimeline(ReplayTimeline.Mode.FRAME, 1, 2);
        require(timeline.advance(10).routeUnit() == 0L, "first frame index");
        require(timeline.advance(10).routeUnit() == 1L, "second frame index");
        require(timeline.advance(10).routeUnit() == 2L, "third frame index");
        require(timeline.advance(10).complete(), "fourth frame must complete");
    }

    private static void sampleBufferGrowsWithoutLosingSamples() {
        LongSampleBuffer samples = new LongSampleBuffer(1);
        for (int value = 0; value < 100; value++) {
            samples.add(value);
        }
        long[] copy = samples.toArray();
        require(copy.length == 100, "sample length");
        for (int index = 0; index < copy.length; index++) {
            require(copy[index] == index, "sample order");
        }
    }

    private static void shaderReloadMetricsAccumulateCompleteBoundary() {
        ShaderReloadMetrics.Snapshot before = ShaderReloadMetrics.snapshot();
        ShaderReloadMetrics.record(10L);
        ShaderReloadMetrics.record(25L);
        ShaderReloadMetrics.Snapshot after = ShaderReloadMetrics.snapshot();
        require(after.reloads() == before.reloads() + 2L, "shader reload count");
        require(after.totalNanos() == before.totalNanos() + 35L, "shader reload total");
        require(after.maximumNanos() >= 25L, "shader reload maximum");
        require(after.lastNanos() == 25L, "shader reload last boundary");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
