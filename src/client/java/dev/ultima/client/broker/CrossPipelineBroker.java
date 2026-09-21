package dev.ultima.client.broker;

import dev.ultima.broker.AdmissionController;
import dev.ultima.config.LoadedModCache;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/** Process-wide render-thread broker runtime. Module gating is resolved before its Mixins apply. */
public final class CrossPipelineBroker {
    private static final AdmissionController.Mode MODE = AdmissionController.Mode.parse(
            System.getProperty("ultima.crossPipelineAdmissionBroker.mode", "trace"));
    private static final AdmissionController CONTROLLER = new AdmissionController(MODE, config());
    private static final BrokerMetrics METRICS = new BrokerMetrics();
    private static final List<GarbageCollectorMXBean> GC = ManagementFactory.getGarbageCollectorMXBeans();
    private static final boolean C2ME_PRESENT = LoadedModCache.isLoaded("c2me");

    private static long frameStartedNanos;
    private static boolean updateImmediately;
    private static double previousCameraX = Double.NaN;
    private static double previousCameraY;
    private static double previousCameraZ;
    private static long lastGcSampleNanos;
    private static long lastGcCollections;
    private static long gcCollectionsDuringFrames;

    private CrossPipelineBroker() {
    }

    public static void beginFrame() {
        frameStartedNanos = System.nanoTime();
    }

    public static void endFrame(final long cpuFrameNanos, final long gpuFrameNanos) {
        long now = System.nanoTime();
        long wall = frameStartedNanos == 0L ? 0L : Math.max(0L, now - frameStartedNanos);
        frameStartedNanos = 0L;
        long serverTick = integratedServerTickNanos();
        CONTROLLER.onFrame(now, cpuFrameNanos > 0L ? cpuFrameNanos : wall, gpuFrameNanos, serverTick);
        METRICS.frames++;
        METRICS.frameWallNanos += wall;
        METRICS.frameCpuNanos += Math.max(0L, cpuFrameNanos);
        if (gpuFrameNanos > 0L) {
            METRICS.gpuFrameNanos += gpuFrameNanos;
        }
        sampleGc(now);
    }

    public static void beginSodiumUpdate(final boolean immediate) {
        updateImmediately = immediate;
    }

    public static void endSodiumUpdate(
            final int queueDepth,
            final int busyWorkers,
            final int totalWorkers) {
        recordPressure(queueDepth, busyWorkers, totalWorkers);
        updateImmediately = false;
    }

    /** Called at HEAD of Sodium's deferred method, before its first dequeue. */
    public static boolean permitDeferredAdmission(
            final int queueDepth,
            final int busyWorkers,
            final int totalWorkers) {
        recordPressure(queueDepth, busyWorkers, totalWorkers);
        METRICS.admissionAttempts++;
        boolean urgent = updateImmediately;
        boolean permit = CONTROLLER.permit(System.nanoTime(), urgent);
        if (urgent) {
            METRICS.urgentBypasses++;
        }
        if (permit) {
            METRICS.admissions++;
        } else {
            METRICS.deferrals++;
        }
        return permit;
    }

    public static void sodiumTaskSubmitted(final long pendingAgeNanos) {
        METRICS.sodiumTaskSubmissions++;
        long age = Math.max(0L, pendingAgeNanos);
        METRICS.pendingAgeNanosTotal += age;
        METRICS.maximumPendingAgeNanos = Math.max(METRICS.maximumPendingAgeNanos, age);
    }

    public static void workerTaskStarted() {
        METRICS.workerTaskStarted();
    }

    public static void workerTaskCompleted() {
        METRICS.workerTaskCompleted();
    }

    public static void uploadStarted(final long bytes) {
        METRICS.uploadBatches++;
        METRICS.bytesUploaded += Math.max(0L, bytes);
    }

    public static void uploadCompleted(final long nanos) {
        METRICS.uploadCompletions++;
        METRICS.uploadNanos += Math.max(0L, nanos);
    }

    public static void firstRenderable(final long requestAgeNanos) {
        long age = Math.max(0L, requestAgeNanos);
        METRICS.firstRenderableCount++;
        METRICS.requestToFirstRenderableNanosTotal += age;
        METRICS.maximumRequestToFirstRenderableNanos = Math.max(
                METRICS.maximumRequestToFirstRenderableNanos, age);
    }

    public static void cameraPosition(final double x, final double y, final double z) {
        if (!Double.isNaN(previousCameraX)) {
            double dx = x - previousCameraX;
            double dy = y - previousCameraY;
            double dz = z - previousCameraZ;
            if (dx * dx + dy * dy + dz * dz > 128.0 * 128.0) {
                reset("camera_teleport");
            }
        }
        previousCameraX = x;
        previousCameraY = y;
        previousCameraZ = z;
    }

    public static void reset(final String reason) {
        CONTROLLER.reset();
        updateImmediately = false;
        previousCameraX = Double.NaN;
        frameStartedNanos = 0L;
        METRICS.resets++;
    }

    public static BrokerMetrics.Snapshot snapshot() {
        AdmissionController.Snapshot controller = CONTROLLER.snapshot();
        BrokerMetrics.AdmissionControllerView view = new BrokerMetrics.AdmissionControllerView(
                controller.mode().name().toLowerCase(java.util.Locale.ROOT),
                controller.pressureHigh(),
                controller.samples(),
                controller.p95FrameNanos(),
                controller.p99FrameNanos(),
                controller.p999FrameNanos(),
                controller.maximumObservedDeferredNanos());
        return METRICS.snapshot(view);
    }

    public static String mode() {
        return MODE.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean changesScheduling() {
        return MODE != AdmissionController.Mode.TRACE;
    }

    public static boolean c2mePresent() {
        return C2ME_PRESENT;
    }

    public static String c2meObserverState() {
        return C2ME_PRESENT ? "present_no_stable_public_pressure_api" : "not_present";
    }

    public static long gcCollectionsDuringFrames() {
        return gcCollectionsDuringFrames;
    }

    private static void recordPressure(final int queueDepth, final int busyWorkers, final int totalWorkers) {
        CONTROLLER.onPipelinePressure(queueDepth, busyWorkers, totalWorkers);
        METRICS.lastQueueDepth = Math.max(0, queueDepth);
        METRICS.maximumQueueDepth = Math.max(METRICS.maximumQueueDepth, METRICS.lastQueueDepth);
        METRICS.lastBusyWorkers = Math.max(0, busyWorkers);
        METRICS.lastTotalWorkers = Math.max(0, totalWorkers);
    }

    private static long integratedServerTickNanos() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server == null ? -1L : server.getAverageTickTimeNanos();
    }

    private static void sampleGc(final long now) {
        if (now - lastGcSampleNanos < 1_000_000_000L) {
            return;
        }
        long collections = 0L;
        for (GarbageCollectorMXBean collector : GC) {
            long count = collector.getCollectionCount();
            if (count > 0L) {
                collections += count;
            }
        }
        if (lastGcSampleNanos != 0L && collections > lastGcCollections) {
            gcCollectionsDuringFrames += collections - lastGcCollections;
        }
        lastGcCollections = collections;
        lastGcSampleNanos = now;
    }

    private static AdmissionController.Config config() {
        return new AdmissionController.Config(
                positiveLong("ultima.crossPipelineAdmissionBroker.targetFrameNanos", 16_666_667L),
                positiveLong("ultima.crossPipelineAdmissionBroker.staleTelemetryNanos", 500_000_000L),
                nonNegativeLong("ultima.crossPipelineAdmissionBroker.minimumPermitIntervalNanos", 50_000_000L),
                positiveLong("ultima.crossPipelineAdmissionBroker.maximumDeferNanos", 250_000_000L),
                positiveInteger("ultima.crossPipelineAdmissionBroker.staticPermitEveryFrames", 2));
    }

    private static long positiveLong(final String key, final long fallback) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : fallback;
    }

    private static long nonNegativeLong(final String key, final long fallback) {
        Long value = Long.getLong(key);
        return value != null && value >= 0L ? value : fallback;
    }

    private static int positiveInteger(final String key, final int fallback) {
        int value = Integer.getInteger(key, fallback);
        return value > 0 ? value : fallback;
    }
}
