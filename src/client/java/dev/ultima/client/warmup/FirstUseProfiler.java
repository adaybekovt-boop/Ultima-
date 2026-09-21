package dev.ultima.client.warmup;

import java.lang.StackWalker.StackFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded first/repeat-touch profiler for rare render initialization operations. */
public final class FirstUseProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-render-first-use");
    private static final int MAX_OPERATIONS = 2_048;
    private static final boolean DETAILED = Boolean.getBoolean("ultima.renderWarmupSystem.detailedProfiler");
    private static final long HITCH_NANOS = positiveLong(
            "ultima.renderWarmupSystem.hitchThresholdNanos", 8_000_000L);
    private static final Map<String, OperationStats> OPERATIONS = new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> IN_WARMUP = ThreadLocal.withInitial(() -> false);

    private static volatile long frameId;
    private static long frameStartedNanos;
    private static volatile boolean frameHadFirstTouch;
    private static volatile boolean warmupCompleted;
    private static long hitchesBefore;
    private static long hitchesAfter;
    private static long maximumFirstUseFrameNanos;
    private static final long[] FIRST_USE_FRAMES = new long[512];
    private static int firstUseFrameCount;
    private static int firstUseFrameCursor;

    private FirstUseProfiler() {
    }

    public static Token begin(final String subsystem, final String operation) {
        String key = subsystem + ':' + operation;
        synchronized (OPERATIONS) {
            OperationStats stats = OPERATIONS.get(key);
            if (stats == null) {
                if (OPERATIONS.size() >= MAX_OPERATIONS) {
                    return Token.IGNORED;
                }
                stats = new OperationStats(subsystem, operation, stackFingerprint());
                OPERATIONS.put(key, stats);
            }
            if (Boolean.TRUE.equals(IN_WARMUP.get())) {
                return stats.warmupNanos < 0L
                        ? new Token(stats, System.nanoTime(), TouchKind.WARMUP, frameId)
                        : Token.IGNORED;
            }
            if (stats.firstNanos < 0L) {
                return new Token(stats, System.nanoTime(), TouchKind.FIRST_NATURAL, frameId);
            }
            if (stats.repeatNanos < 0L) {
                return new Token(stats, System.nanoTime(), TouchKind.REPEAT, frameId);
            }
            return Token.IGNORED;
        }
    }

    public static void end(final Token token) {
        if (token == null || token == Token.IGNORED) {
            return;
        }
        long duration = Math.max(0L, System.nanoTime() - token.startedNanos);
        synchronized (OPERATIONS) {
            if (token.kind == TouchKind.WARMUP && token.stats.warmupNanos < 0L) {
                token.stats.warmupNanos = duration;
            } else if (token.kind == TouchKind.FIRST_NATURAL && token.stats.firstNanos < 0L) {
                token.stats.firstNanos = duration;
                token.stats.firstFrame = token.frame;
                token.stats.warmedBeforeFirstUse = token.stats.warmupNanos >= 0L;
                frameHadFirstTouch = true;
                if (duration >= HITCH_NANOS) {
                    if (warmupCompleted) {
                        hitchesAfter++;
                    } else {
                        hitchesBefore++;
                    }
                }
                if (DETAILED) {
                    LOGGER.info(
                            "first_touch operation={} duration_ms={} frame={} subsystem={} stack={}",
                            token.stats.operation,
                            duration / 1_000_000.0,
                            token.frame,
                            token.stats.subsystem,
                            token.stats.stackFingerprint);
                }
            } else if (token.kind == TouchKind.REPEAT && token.stats.repeatNanos < 0L) {
                token.stats.repeatNanos = duration;
            }
        }
    }

    public static void beginFrame() {
        frameId++;
        frameStartedNanos = System.nanoTime();
        frameHadFirstTouch = false;
    }

    public static void endFrame() {
        long duration = frameStartedNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - frameStartedNanos);
        frameStartedNanos = 0L;
        if (!frameHadFirstTouch || duration <= 0L) {
            return;
        }
        maximumFirstUseFrameNanos = Math.max(maximumFirstUseFrameNanos, duration);
        FIRST_USE_FRAMES[firstUseFrameCursor] = duration;
        firstUseFrameCursor = (firstUseFrameCursor + 1) % FIRST_USE_FRAMES.length;
        firstUseFrameCount = Math.min(FIRST_USE_FRAMES.length, firstUseFrameCount + 1);
    }

    public static void markWarmupCompleted() {
        warmupCompleted = true;
    }

    public static void beginWarmup() {
        IN_WARMUP.set(true);
    }

    public static void endWarmup() {
        IN_WARMUP.remove();
    }

    public static Snapshot snapshot() {
        List<OperationSnapshot> operations = new ArrayList<>();
        synchronized (OPERATIONS) {
            for (OperationStats stats : OPERATIONS.values()) {
                operations.add(new OperationSnapshot(
                        stats.subsystem,
                        stats.operation,
                        stats.warmupNanos,
                        stats.firstNanos,
                        stats.repeatNanos,
                        stats.firstFrame,
                        stats.stackFingerprint,
                        stats.warmedBeforeFirstUse));
            }
        }
        operations.sort(Comparator.comparingLong(OperationSnapshot::firstNanos).reversed());
        long[] frames = new long[firstUseFrameCount];
        System.arraycopy(FIRST_USE_FRAMES, 0, frames, 0, firstUseFrameCount);
        java.util.Arrays.sort(frames);
        return new Snapshot(
                operations.size(),
                hitchesBefore,
                hitchesAfter,
                maximumFirstUseFrameNanos,
                percentile(frames, 0.99),
                List.copyOf(operations));
    }

    private static String stackFingerprint() {
        if (!DETAILED) {
            return "disabled";
        }
        try {
            long hash = StackWalker.getInstance().walk(stream -> stream
                    .filter(frame -> !frame.getClassName().startsWith("dev.ultima.client.warmup"))
                    .limit(8)
                    .mapToLong(FirstUseProfiler::hashFrame)
                    .reduce(0xcbf29ce484222325L, (left, right) -> (left ^ right) * 0x100000001b3L));
            return Long.toUnsignedString(hash, 16);
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static long hashFrame(final StackFrame frame) {
        long hash = 0xcbf29ce484222325L;
        String text = frame.getClassName() + '#' + frame.getMethodName();
        for (int index = 0; index < text.length(); index++) {
            hash = (hash ^ text.charAt(index)) * 0x100000001b3L;
        }
        return hash;
    }

    private static long percentile(final long[] sorted, final double value) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = Math.max(0, Math.min(sorted.length - 1, (int)Math.ceil(sorted.length * value) - 1));
        return sorted[index];
    }

    private static long positiveLong(final String key, final long fallback) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : fallback;
    }

    public static final class Token {
        private static final Token IGNORED = new Token(null, 0L, TouchKind.IGNORED, 0L);

        private final OperationStats stats;
        private final long startedNanos;
        private final TouchKind kind;
        private final long frame;

        private Token(final OperationStats stats, final long startedNanos, final TouchKind kind, final long frame) {
            this.stats = stats;
            this.startedNanos = startedNanos;
            this.kind = kind;
            this.frame = frame;
        }
    }

    private static final class OperationStats {
        final String subsystem;
        final String operation;
        final String stackFingerprint;
        long warmupNanos = -1L;
        long firstNanos = -1L;
        long repeatNanos = -1L;
        long firstFrame = -1L;
        boolean warmedBeforeFirstUse;

        OperationStats(final String subsystem, final String operation, final String stackFingerprint) {
            this.subsystem = subsystem;
            this.operation = operation;
            this.stackFingerprint = stackFingerprint;
        }
    }

    public record OperationSnapshot(
            String subsystem,
            String operation,
            long warmupNanos,
            long firstNanos,
            long repeatNanos,
            long firstFrame,
            String stackFingerprint,
            boolean warmedBeforeFirstUse) {
    }

    public record Snapshot(
            int operations,
            long firstUseHitchesBefore,
            long firstUseHitchesAfter,
            long maximumFirstUseFrameNanos,
            long p99FirstUseFrameNanos,
            List<OperationSnapshot> slowestOperations) {
    }

    private enum TouchKind {
        WARMUP,
        FIRST_NATURAL,
        REPEAT,
        IGNORED
    }
}
