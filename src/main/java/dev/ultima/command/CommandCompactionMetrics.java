package dev.ultima.command;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command compaction counters for later long chunk-flight A/B. Not a
 * performance claim.
 */
public final class CommandCompactionMetrics {
    private static final AtomicInteger COMPACTION_COUNT = new AtomicInteger();
    private static final AtomicInteger COMMANDS_BEFORE = new AtomicInteger();
    private static final AtomicInteger COMMANDS_AFTER = new AtomicInteger();
    private static final AtomicLong COMPACTION_CPU_NS = new AtomicLong();
    private static final AtomicLong BYTES_REWRITTEN = new AtomicLong();
    private static final AtomicInteger MAX_DEAD_RATIO_MILLI = new AtomicInteger();

    private CommandCompactionMetrics() {
    }

    public static void reset() {
        COMPACTION_COUNT.set(0);
        COMMANDS_BEFORE.set(0);
        COMMANDS_AFTER.set(0);
        COMPACTION_CPU_NS.set(0L);
        BYTES_REWRITTEN.set(0L);
        MAX_DEAD_RATIO_MILLI.set(0);
    }

    public static void record(final CommandCompactor.Result result, final long cpuNs) {
        if (!result.compacted()) {
            return;
        }
        COMPACTION_COUNT.incrementAndGet();
        COMMANDS_BEFORE.addAndGet(result.commandsBefore());
        COMMANDS_AFTER.addAndGet(result.commandsAfter());
        COMPACTION_CPU_NS.addAndGet(cpuNs);
        BYTES_REWRITTEN.addAndGet(result.bytesRewritten());
        int milli = (int)Math.round(result.deadRatioBefore() * 1000.0);
        MAX_DEAD_RATIO_MILLI.accumulateAndGet(milli, Math::max);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                COMPACTION_COUNT.get(),
                COMMANDS_BEFORE.get(),
                COMMANDS_AFTER.get(),
                COMPACTION_CPU_NS.get(),
                BYTES_REWRITTEN.get(),
                MAX_DEAD_RATIO_MILLI.get() / 1000.0);
    }

    public record Snapshot(
            int compactionCount,
            int commandsBefore,
            int commandsAfter,
            long compactionCpuNs,
            long bytesRewritten,
            double maxDeadRatio) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"commandCompactionMetrics\": {\n")
                    .append("    \"compactionCount\": ").append(this.compactionCount).append(",\n")
                    .append("    \"commandsBefore\": ").append(this.commandsBefore).append(",\n")
                    .append("    \"commandsAfter\": ").append(this.commandsAfter).append(",\n")
                    .append("    \"compactionCpuNs\": ").append(this.compactionCpuNs).append(",\n")
                    .append("    \"bytesRewritten\": ").append(this.bytesRewritten).append(",\n")
                    .append("    \"maxDeadRatio\": ").append(this.maxDeadRatio).append("\n")
                    .append("  }");
        }
    }
}
