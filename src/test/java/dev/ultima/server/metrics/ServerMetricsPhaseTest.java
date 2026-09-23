package dev.ultima.server.metrics;

import dev.ultima.review.BytecodeContracts;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Phase samples and tick-span recovery. A phase that never opened must not become a zero
 * percentile sample, and an unclosed span must be closed by {@code endTick}.
 */
public final class ServerMetricsPhaseTest {
    private ServerMetricsPhaseTest() {
    }

    public static void main(final String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        long[] now = {0L};
        ServerMetrics.resetForTest(true, () -> now[0], 16);
        now[0] = 10L;
        ServerMetrics.beginTick(1L, 0);
        ServerMetrics.begin(MetricId.TICK_TOTAL);
        now[0] = 40L;
        ServerMetrics.endTick();
        ServerMetrics.MetricSnapshot total = ServerMetrics.snapshotOf(MetricId.TICK_TOTAL);
        if (total.windowCount() != 1 || total.last() != 30L) {
            throw new AssertionError("unclosed tick.total must be recovered once, got " + total);
        }
        ServerMetrics.MetricSnapshot entities = ServerMetrics.snapshotOf(MetricId.TICK_ENTITIES);
        if (entities.windowCount() != 0) {
            throw new AssertionError("a phase that did not run must not receive a zero sample");
        }

        ServerMetrics.beginTick(2L, 0);
        ServerMetrics.endTick();
        if (ServerMetrics.snapshotOf(MetricId.TICK_TOTAL).windowCount() != 1) {
            throw new AssertionError("a tick that did not open tick.total must not pad the ring");
        }
        if (ServerMetrics.snapshotOf(MetricId.TICK_ENTITIES).windowCount() != 0) {
            throw new AssertionError("tick.entities stayed unopened and must stay absent");
        }

        ClassNode tick = BytecodeContracts.load("dev.ultima.mixin.server_metrics.MinecraftServerMixin");
        boolean recovered = false;
        for (MethodNode method : tick.methods) {
            if (method.tryCatchBlocks != null
                    && !method.tryCatchBlocks.isEmpty()
                    && BytecodeContracts.invokes(tick, "dev/ultima/server/metrics/ServerMetrics", "endTick")) {
                recovered = true;
            }
        }
        if (!recovered) {
            throw new AssertionError("tickServer must close the phase clock from a finally block");
        }
        ClassNode connection = BytecodeContracts.load("dev.ultima.mixin.server_metrics.ConnectionMixin");
        if (!BytecodeContracts.invokes(connection, "io/netty/channel/EventLoop", "inEventLoop")) {
            throw new AssertionError("queued-byte sampling must stay on the Netty event loop");
        }
        ServerMetrics.resetForTest(false, null, ServerMetrics.DEFAULT_RING_SIZE);
        System.out.println("Server metrics phase checks passed.");
    }
}
