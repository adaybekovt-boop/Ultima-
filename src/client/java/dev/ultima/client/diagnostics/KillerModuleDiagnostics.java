package dev.ultima.client.diagnostics;

import dev.ultima.UltimaBuildInfo;
import dev.ultima.cache.iris.ArtifactCacheMetrics;
import dev.ultima.client.broker.BrokerMetrics;
import dev.ultima.client.broker.CrossPipelineBroker;
import dev.ultima.client.iris.cache.IrisFrontendArtifactCache;
import dev.ultima.client.warmup.FirstUseProfiler;
import dev.ultima.client.warmup.WarmupCoordinator;
import dev.ultima.config.KillerModuleCompatibility;
import dev.ultima.config.KillerModuleCompatibility.AdapterState;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.settings.SettingsRowView;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** On-demand UI and JSON diagnostics for the three independent experimental modules. */
public final class KillerModuleDiagnostics {
    private static final List<String> MODULES = List.of(
            KillerModuleCompatibility.IRIS_MODULE,
            KillerModuleCompatibility.BROKER_MODULE,
            KillerModuleCompatibility.WARMUP_MODULE);

    private KillerModuleDiagnostics() {
    }

    public static String uiStatus(final SettingsRowView row, final UltimaConfig config) {
        ModuleRuntime runtime = runtime(row.key(), config);
        AdapterState adapter = KillerModuleCompatibility.state(row.key());
        String disabled = runtime.active() ? "none" : row.statusReason() + ": " + row.statusDetail();
        String adapterVersion = adapter.modId().isBlank()
                ? adapter.state()
                : adapter.modId() + '@' + emptyAs(adapter.version(), "unknown") + " / " + adapter.state();
        return "Requested: " + row.requested()
                + "\nSupported: " + adapter.supported()
                + "\nActive: " + runtime.active()
                + "\nMode: " + runtime.mode()
                + "\nFail-closed reason: " + runtime.failClosedReason()
                + "\nDisabled reason: " + disabled
                + "\nAdapter/version state: " + adapterVersion
                + "\nLast failure: " + runtime.failureState();
    }

    public static String chatSummary(final UltimaConfig config) {
        StringBuilder text = new StringBuilder("Ultima killer modules\n");
        for (String key : MODULES) {
            UltimaConfig.ResolvedModule resolved = config.resolve(key);
            ModuleRuntime runtime = runtime(key, config);
            AdapterState adapter = KillerModuleCompatibility.state(key);
            text.append(key)
                    .append(" requested=").append(resolved.requested())
                    .append(" active=").append(runtime.active())
                    .append(" reason=").append(runtime.active() ? "active" : resolved.reason())
                    .append(" adapter=").append(adapter.state())
                    .append(" runtime=").append(runtime.failureState())
                    .append('\n');
        }
        return text.toString();
    }

    public static Path defaultOutput() {
        return FabricLoader.getInstance().getGameDir().resolve("ultima-killer-modules-diagnostics.json");
    }

    public static Path writeDefault(final UltimaConfig config) throws IOException {
        Path target = defaultOutput();
        writeAtomically(target, toJson(config));
        return target;
    }

    public static String toJson(final UltimaConfig config) {
        StringBuilder json = new StringBuilder(24_576);
        json.append("{\n  \"schemaVersion\": 1,\n");
        json.append("  \"environment\": {\n")
                .append("    \"minecraft\": ").append(quote(modVersion("minecraft"))).append(",\n")
                .append("    \"ultima\": ").append(quote(UltimaBuildInfo.version())).append(",\n")
                .append("    \"ultimaGitSha\": ").append(quote(UltimaBuildInfo.gitSha())).append(",\n")
                .append("    \"iris\": ").append(quote(modVersion("iris"))).append(",\n")
                .append("    \"sodium\": ").append(quote(modVersion("sodium"))).append(",\n")
                .append("    \"lithium\": ").append(quote(modVersion("lithium"))).append(",\n")
                .append("    \"c2me\": ").append(quote(modVersion("c2me"))).append(",\n")
                .append("    \"java\": ").append(quote(System.getProperty("java.runtime.version", ""))).append(",\n")
                .append("    \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors()).append('\n')
                .append("  },\n");

        json.append("  \"modules\": [\n");
        for (int index = 0; index < MODULES.size(); index++) {
            String key = MODULES.get(index);
            UltimaConfig.ResolvedModule resolved = config.resolve(key);
            AdapterState adapter = KillerModuleCompatibility.state(key);
            ModuleRuntime runtime = runtime(key, config);
            json.append("    {\n")
                    .append("      \"key\": ").append(quote(key)).append(",\n")
                    .append("      \"requested\": ").append(resolved.requested()).append(",\n")
                    .append("      \"active\": ").append(runtime.active()).append(",\n")
                    .append("      \"appliedAtLaunch\": ").append(config.wasEnabledAtLaunch(key)).append(",\n")
                    .append("      \"disabledReason\": ").append(quote(runtime.active() ? "" : resolved.reason())).append(",\n")
                    .append("      \"disabledDetail\": ").append(quote(runtime.active() ? "" : resolved.detail())).append(",\n")
                    .append("      \"runtimeState\": ").append(quote(runtime.runtimeState())).append(",\n")
                    .append("      \"mode\": ").append(quote(runtime.mode())).append(",\n")
                    .append("      \"failClosedReason\": ").append(quote(runtime.failClosedReason())).append(",\n")
                    .append("      \"failureFailOpenState\": ").append(quote(runtime.failureState())).append(",\n")
                    .append("      \"adapter\": {\n")
                    .append("        \"supported\": ").append(adapter.supported()).append(",\n")
                    .append("        \"state\": ").append(quote(adapter.state())).append(",\n")
                    .append("        \"modId\": ").append(quote(adapter.modId())).append(",\n")
                    .append("        \"version\": ").append(quote(adapter.version())).append(",\n")
                    .append("        \"fingerprint\": ").append(quote(adapter.fingerprint())).append(",\n")
                    .append("        \"detail\": ").append(quote(adapter.detail())).append('\n')
                    .append("      }\n")
                    .append("    }");
            if (index + 1 < MODULES.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ],\n");
        appendArtifactCache(json, config);
        json.append(",\n");
        appendBroker(json, config);
        json.append(",\n");
        appendWarmup(json, config);
        json.append("\n}\n");
        return json.toString();
    }

    private static void appendArtifactCache(final StringBuilder json, final UltimaConfig config) {
        boolean applied = config.wasEnabledAtLaunch(KillerModuleCompatibility.IRIS_MODULE);
        ArtifactCacheMetrics.Snapshot metrics = applied ? IrisFrontendArtifactCache.snapshot() : null;
        json.append("  \"artifactCache\": {\n")
                .append("    \"available\": ").append(applied).append(",\n")
                .append("    \"activation\": ")
                .append(quote(applied ? IrisFrontendArtifactCache.activationState() : "module_not_applied")).append(",\n")
                .append("    \"contractProven\": ").append(applied && IrisFrontendArtifactCache.contractProven()).append(",\n")
                .append("    \"verifyMode\": ").append(applied && IrisFrontendArtifactCache.verifyMode()).append(",\n")
                .append("    \"failedOpen\": ").append(applied && IrisFrontendArtifactCache.failedOpen()).append(",\n")
                .append("    \"failureReason\": ").append(quote(applied ? IrisFrontendArtifactCache.failureReason() : "module_not_applied"));
        if (metrics != null) {
            json.append(",\n")
                    .append("    \"requests\": ").append(metrics.requests()).append(",\n")
                    .append("    \"hits\": ").append(metrics.hits()).append(",\n")
                    .append("    \"misses\": ").append(metrics.misses()).append(",\n")
                    .append("    \"invalidations\": ").append(metrics.invalidations()).append(",\n")
                    .append("    \"corruptions\": ").append(metrics.corruptions()).append(",\n")
                    .append("    \"readFailures\": ").append(metrics.readFailures()).append(",\n")
                    .append("    \"writeFailures\": ").append(metrics.writeFailures()).append(",\n")
                    .append("    \"transformNsSavedEstimate\": ").append(metrics.transformNanosSaved()).append(",\n")
                    .append("    \"frontendTransformNs\": ").append(metrics.frontendTransformNanos()).append(",\n")
                    .append("    \"cacheReadNs\": ").append(metrics.cacheReadNanos()).append(",\n")
                    .append("    \"cacheWriteNs\": ").append(metrics.cacheWriteNanos()).append(",\n")
                    .append("    \"bytesRead\": ").append(metrics.bytesRead()).append(",\n")
                    .append("    \"bytesWritten\": ").append(metrics.bytesWritten()).append(",\n")
                    .append("    \"cacheSizeBytes\": ").append(metrics.cacheSizeBytes()).append(",\n")
                    .append("    \"entries\": ").append(metrics.entries()).append(",\n")
                    .append("    \"verifyMatches\": ").append(metrics.verifyMatches()).append(",\n")
                    .append("    \"verifyMismatches\": ").append(metrics.verifyMismatches()).append(",\n")
                    .append("    \"reloads\": ").append(metrics.reloads()).append(",\n")
                    .append("    \"sampleReloads\": ").append(metrics.sampleReloads()).append(",\n")
                    .append("    \"lastUnkeyableReason\": ").append(quote(metrics.lastUnkeyableReason())).append(",\n")
                    .append("    \"lastMissReason\": ").append(quote(metrics.lastMissReason())).append(",\n")
                    .append("    \"reloadWallNs\": ").append(metrics.reloadNanos()).append(",\n")
                    .append("    \"downstreamCompileNs\": null,\n")
                    .append("    \"linkNs\": null,\n")
                    .append("    \"downstreamTimingState\": \"unavailable_without_semantics_safe_iris_hook\"");
        }
        json.append("\n  }");
    }

    private static void appendBroker(final StringBuilder json, final UltimaConfig config) {
        boolean applied = config.wasEnabledAtLaunch(KillerModuleCompatibility.BROKER_MODULE);
        BrokerMetrics.Snapshot metrics = applied ? CrossPipelineBroker.snapshot() : null;
        json.append("  \"admissionBroker\": {\n")
                .append("    \"available\": ").append(applied).append(",\n")
                .append("    \"mode\": ").append(quote(applied ? CrossPipelineBroker.mode() : "module_not_applied")).append(",\n")
                .append("    \"requestedMode\": ")
                .append(quote(applied ? CrossPipelineBroker.requestedMode() : "module_not_applied")).append(",\n")
                .append("    \"controlAvailable\": false,\n")
                .append("    \"controlUnavailableReason\": ")
                .append(quote(applied ? CrossPipelineBroker.controlUnavailableReason() : "module_not_applied")).append(",\n")
                .append("    \"changesScheduling\": false,\n")
                .append("    \"failedOpen\": ").append(applied && CrossPipelineBroker.failedOpen()).append(",\n")
                .append("    \"failureReason\": ")
                .append(quote(applied ? CrossPipelineBroker.failureReason() : "module_not_applied")).append(",\n")
                .append("    \"c2meObserverState\": ").append(quote(applied ? CrossPipelineBroker.c2meObserverState() : "module_not_applied"));
        if (metrics != null) {
            json.append(",\n")
                    .append("    \"frames\": ").append(metrics.frames()).append(",\n")
                    .append("    \"frameWallNsTotal\": ").append(metrics.frameWallNanosTotal()).append(",\n")
                    .append("    \"frameCpuNsTotal\": ").append(metrics.frameCpuNanosTotal()).append(",\n")
                    .append("    \"gpuFrameNsTotal\": ").append(metrics.gpuFrameNanosTotal()).append(",\n")
                    .append("    \"gpuSamplesNoData\": ").append(metrics.gpuSamplesNoData()).append(",\n")
                    .append("    \"gpuSamplesZero\": ").append(metrics.gpuSamplesZero()).append(",\n")
                    .append("    \"gpuSamplesValid\": ").append(metrics.gpuSamplesValid()).append(",\n")
                    .append("    \"admissionAttempts\": ").append(metrics.admissionAttempts()).append(",\n")
                    .append("    \"admissions\": ").append(metrics.admissions()).append(",\n")
                    .append("    \"deferrals\": ").append(metrics.deferrals()).append(",\n")
                    .append("    \"urgentBypasses\": ").append(metrics.urgentBypasses()).append(",\n")
                    .append("    \"starvationBypasses\": ").append(metrics.starvationBypasses()).append(",\n")
                    .append("    \"taskSubmissions\": ").append(metrics.sodiumTaskSubmissions()).append(",\n")
                    .append("    \"meshReadyResults\": ").append(metrics.meshReadyResults()).append(",\n")
                    .append("    \"taskStarts\": ").append(metrics.workerTaskStarts()).append(",\n")
                    .append("    \"taskCompletions\": ").append(metrics.workerTaskCompletions()).append(",\n")
                    .append("    \"observationDurationNs\": ").append(metrics.observationDurationNanos()).append(",\n")
                    .append("    \"taskCompletionThroughputPerSecond\": ")
                    .append(metrics.observationDurationNanos() == 0L
                            ? 0.0
                            : metrics.workerTaskCompletions() * 1_000_000_000.0 / metrics.observationDurationNanos())
                    .append(",\n")
                    .append("    \"pendingAgeNsTotal\": ").append(metrics.pendingAgeNanosTotal()).append(",\n")
                    .append("    \"maximumPendingAgeNs\": ").append(metrics.maximumPendingAgeNanos()).append(",\n")
                    .append("    \"pendingAgeSampleCount\": ").append(metrics.pendingAgeSampleCount()).append(",\n")
                    .append("    \"p95PendingAgeNs\": ").append(metrics.p95PendingAgeNanos()).append(",\n")
                    .append("    \"p99PendingAgeNs\": ").append(metrics.p99PendingAgeNanos()).append(",\n")
                    .append("    \"uploadBatches\": ").append(metrics.uploadBatches()).append(",\n")
                    .append("    \"uploadCompletions\": ").append(metrics.uploadCompletions()).append(",\n")
                    .append("    \"uploadNs\": ").append(metrics.uploadNanos()).append(",\n")
                    .append("    \"bytesUploaded\": ").append(metrics.bytesUploaded()).append(",\n")
                    .append("    \"firstVisibleProxyCount\": ").append(metrics.firstRenderableCount()).append(",\n")
                    .append("    \"requestToFirstVisibleProxyNsTotal\": ").append(metrics.requestToFirstRenderableNanosTotal()).append(",\n")
                    .append("    \"maximumRequestToFirstVisibleProxyNs\": ").append(metrics.maximumRequestToFirstRenderableNanos()).append(",\n")
                    .append("    \"firstVisibleProxySampleCount\": ").append(metrics.firstRenderableSampleCount()).append(",\n")
                    .append("    \"p95RequestToFirstVisibleProxyNs\": ").append(metrics.p95RequestToFirstRenderableNanos()).append(",\n")
                    .append("    \"p99RequestToFirstVisibleProxyNs\": ").append(metrics.p99RequestToFirstRenderableNanos()).append(",\n")
                    .append("    \"initialBuildRequests\": ").append(metrics.initialBuildRequests()).append(",\n")
                    .append("    \"cancelledInitialBuilds\": ").append(metrics.cancelledInitialBuilds()).append(",\n")
                    .append("    \"visibleHoleProxyCurrent\": ").append(metrics.outstandingInitialBuilds()).append(",\n")
                    .append("    \"visibleHoleProxyMaximum\": ").append(metrics.maximumOutstandingInitialBuilds()).append(",\n")
                    .append("    \"maximumQueueDepth\": ").append(metrics.maximumQueueDepth()).append(",\n")
                    .append("    \"lastQueueDepth\": ").append(metrics.lastQueueDepth()).append(",\n")
                    .append("    \"lastBusyWorkers\": ").append(metrics.lastBusyWorkers()).append(",\n")
                    .append("    \"lastTotalWorkers\": ").append(metrics.lastTotalWorkers()).append(",\n")
                    .append("    \"observedWorkerThreads\": ").append(metrics.observedWorkerThreads()).append(",\n")
                    .append("    \"gcCollectionsDuringFrames\": ").append(CrossPipelineBroker.gcCollectionsDuringFrames()).append(",\n")
                    .append("    \"resets\": ").append(metrics.resets()).append(",\n")
                    .append("    \"controller\": {\n")
                    .append("      \"pressureHigh\": ").append(metrics.controller().pressureHigh()).append(",\n")
                    .append("      \"frameSamples\": ").append(metrics.controller().frameSamples()).append(",\n")
                    .append("      \"p95CpuFrameNs\": ").append(metrics.controller().p95CpuFrameNanos()).append(",\n")
                    .append("      \"p99CpuFrameNs\": ").append(metrics.controller().p99CpuFrameNanos()).append(",\n")
                    .append("      \"p999CpuFrameNs\": ").append(metrics.controller().p999CpuFrameNanos()).append(",\n")
                    .append("      \"maximumObservedDeferredNs\": ").append(metrics.controller().maximumObservedDeferredNanos()).append('\n')
                    .append("    }");
        }
        json.append("\n  }");
    }

    private static void appendWarmup(final StringBuilder json, final UltimaConfig config) {
        boolean applied = config.wasEnabledAtLaunch(KillerModuleCompatibility.WARMUP_MODULE);
        WarmupCoordinator.Snapshot warmup = applied ? WarmupCoordinator.snapshot() : null;
        FirstUseProfiler.Snapshot profiler = applied ? FirstUseProfiler.snapshot() : null;
        json.append("  \"renderWarmup\": {\n")
                .append("    \"available\": ").append(applied);
        if (warmup != null && profiler != null) {
            json.append(",\n")
                    .append("    \"mode\": ").append(quote(WarmupCoordinator.mode())).append(",\n")
                    .append("    \"requestedMode\": ").append(quote(WarmupCoordinator.requestedMode())).append(",\n")
                    .append("    \"activeWarmup\": false,\n")
                    .append("    \"failClosedReason\": ").append(quote(WarmupCoordinator.failClosedReason())).append(",\n")
                    .append("    \"changesRenderInitialization\": false,\n")
                    .append("    \"state\": ").append(quote(warmup.state())).append(",\n")
                    .append("    \"completedAdapters\": ").append(warmup.completedAdapters()).append(",\n")
                    .append("    \"totalAdapters\": ").append(warmup.totalAdapters()).append(",\n")
                    .append("    \"warmupItemsDiscovered\": ").append(warmup.discoveredItems()).append(",\n")
                    .append("    \"warmupItems\": ").append(warmup.warmedItems()).append(",\n")
                    .append("    \"warmupFailures\": ").append(warmup.failures()).append(",\n")
                    .append("    \"failedOpen\": ").append(WarmupCoordinator.failedOpen()).append(",\n")
                    .append("    \"failureReason\": ").append(quote(WarmupCoordinator.failureReason())).append(",\n")
                    .append("    \"warmupTimeNs\": ").append(warmup.warmupNanos()).append(",\n")
                    .append("    \"memoryDeltaBytes\": ").append(warmup.memoryDeltaBytes()).append(",\n")
                    .append("    \"gpuResourceDelta\": ").append(warmup.gpuResourceDelta()).append(",\n")
                    .append("    \"firstUseProfiledOperations\": ").append(profiler.operations()).append(",\n")
                    .append("    \"firstUseHitchesBefore\": ").append(profiler.firstUseHitchesBefore()).append(",\n")
                    .append("    \"firstUseHitchesAfter\": ").append(profiler.firstUseHitchesAfter()).append(",\n")
                    .append("    \"maxFirstUseFrameNs\": ").append(profiler.maximumFirstUseFrameNanos()).append(",\n")
                    .append("    \"p99FirstUseFrameNs\": ").append(profiler.p99FirstUseFrameNanos()).append(",\n")
                    .append("    \"adapters\": [\n");
            for (int index = 0; index < warmup.adapters().size(); index++) {
                WarmupCoordinator.AdapterStatus adapter = warmup.adapters().get(index);
                json.append("      {\"id\": ").append(quote(adapter.id()))
                        .append(", \"active\": ").append(adapter.active())
                        .append(", \"state\": ").append(quote(adapter.state()))
                        .append(", \"detail\": ").append(quote(adapter.detail())).append('}');
                if (index + 1 < warmup.adapters().size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("    ],\n    \"slowestFirstUseOperations\": [\n");
            int limit = Math.min(64, profiler.slowestOperations().size());
            for (int index = 0; index < limit; index++) {
                FirstUseProfiler.OperationSnapshot operation = profiler.slowestOperations().get(index);
                json.append("      {\"subsystem\": ").append(quote(operation.subsystem()))
                        .append(", \"operation\": ").append(quote(operation.operation()))
                        .append(", \"warmupNs\": ").append(operation.warmupNanos())
                        .append(", \"firstInvocationNs\": ").append(operation.firstNanos())
                        .append(", \"repeatInvocationNs\": ").append(operation.repeatNanos())
                        .append(", \"firstFrame\": ").append(operation.firstFrame())
                        .append(", \"warmedBeforeFirstUse\": ").append(operation.warmedBeforeFirstUse())
                        .append(", \"stackFingerprint\": ").append(quote(operation.stackFingerprint())).append('}');
                if (index + 1 < limit) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("    ]");
        }
        json.append("\n  }");
    }

    private static ModuleRuntime runtime(final String key, final UltimaConfig config) {
        if (!config.wasEnabledAtLaunch(key)) {
            return new ModuleRuntime(false, "not_applied_at_launch", "inactive", "off", "not_applied");
        }
        return switch (key) {
            case KillerModuleCompatibility.IRIS_MODULE -> {
                if (IrisFrontendArtifactCache.failedOpen()) {
                    yield new ModuleRuntime(
                            false,
                            "failed_open",
                            IrisFrontendArtifactCache.failureReason(),
                            "failed_open",
                            IrisFrontendArtifactCache.failureReason());
                }
                boolean proven = IrisFrontendArtifactCache.contractProven();
                yield new ModuleRuntime(
                        proven,
                        IrisFrontendArtifactCache.activationState(),
                        "none",
                        IrisFrontendArtifactCache.activationState(),
                        proven ? "none" : IrisFrontendArtifactCache.activationState());
            }
            case KillerModuleCompatibility.BROKER_MODULE -> {
                if (CrossPipelineBroker.failedOpen()) {
                    yield new ModuleRuntime(
                            false,
                            "failed_open",
                            CrossPipelineBroker.failureReason(),
                            "observer",
                            CrossPipelineBroker.failureReason());
                }
                boolean traceRequested = "trace".equals(CrossPipelineBroker.requestedMode());
                yield new ModuleRuntime(
                        traceRequested,
                        "observer_only",
                        "none",
                        "observer",
                        CrossPipelineBroker.controlUnavailableReason());
            }
            case KillerModuleCompatibility.WARMUP_MODULE -> WarmupCoordinator.failedOpen()
                    ? new ModuleRuntime(
                            false,
                            "failed_open",
                            WarmupCoordinator.failureReason(),
                            "profiler_only",
                            WarmupCoordinator.failureReason())
                    : new ModuleRuntime(
                            false,
                            "profiler_only",
                            "none",
                            "profiler_only",
                            WarmupCoordinator.failClosedReason());
            default -> new ModuleRuntime(false, "unknown", "unknown_module", "unknown", "unknown_module");
        };
    }

    private static String modVersion(final String id) {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(id)
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeAtomically(final Path target, final String contents) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString() + '.', ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String quote(final String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int)character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String emptyAs(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ModuleRuntime(
            boolean active,
            String runtimeState,
            String failureState,
            String mode,
            String failClosedReason) {
    }
}
