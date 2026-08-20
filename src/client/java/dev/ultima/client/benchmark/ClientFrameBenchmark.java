package dev.ultima.client.benchmark;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import dev.ultima.client.renderer.retained.RetainedVisibilityDebug;
import dev.ultima.client.renderer.retained.RetainedCompactionDebug;
import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaConfig.ResolvedModule;
import dev.ultima.meshing.MesherMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in client frame recorder. Both sides of an A/B run keep this module enabled so recorder
 * overhead is identical; optimization modules are the only changed variables.
 *
 * <p>The primary release comparison is {@code disabled} versus {@code default}. Forcing every
 * experimental module on is an expert bound, not the shipped configuration.
 */
public final class ClientFrameBenchmark {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-client-benchmark");
    private static final boolean ENABLED = Boolean.getBoolean("ultima.clientBenchmark");
    private static final boolean EXIT_AFTER_WRITE = booleanProperty("ultima.clientBenchmark.exitAfterWrite", true);
    private static final boolean CAPTURE_SCREENSHOTS = Boolean.getBoolean("ultima.clientBenchmark.captureScreenshots");
    private static final String SCENE = System.getProperty("ultima.clientBenchmark.scene", "unspecified");
    private static final int WARMUP_FRAMES = positiveIntegerProperty(
            "ultima.clientBenchmark.warmupFrames", defaultWarmupFrames(SCENE));
    private static final int SAMPLE_FRAMES = positiveIntegerProperty(
            "ultima.clientBenchmark.sampleFrames", defaultSampleFrames(SCENE));
    private static final Path OUTPUT = Path.of(
            System.getProperty("ultima.clientBenchmark.output", "run/ultima-client-benchmark.json"));
    private static final String SCREENSHOT_PREFIX = System.getProperty(
            "ultima.clientBenchmark.screenshotPrefix", "ultima-client-benchmark");
    private static final String CAMERA_MODE = System.getProperty(
            "ultima.clientBenchmark.cameraMode",
            cameraModeForScene(SCENE));
    private static final Double CAMERA_X = doubleProperty("ultima.clientBenchmark.cameraX");
    private static final Double CAMERA_Y = doubleProperty("ultima.clientBenchmark.cameraY");
    private static final Double CAMERA_Z = doubleProperty("ultima.clientBenchmark.cameraZ");
    private static final Double CAMERA_YAW = doubleProperty("ultima.clientBenchmark.cameraYaw");
    private static final Double CAMERA_PITCH = doubleProperty("ultima.clientBenchmark.cameraPitch");
    private static final double CAMERA_YAW_PER_FRAME = doubleProperty(
            "ultima.clientBenchmark.cameraYawDegreesPerFrame",
            "yaw_sweep".equals(CAMERA_MODE) || "chunk_flight".equals(CAMERA_MODE) ? 0.25 : 0.0);
    private static final double CAMERA_Z_PER_FRAME = doubleProperty(
            "ultima.clientBenchmark.cameraZPerFrame",
            "chunk_flight".equals(CAMERA_MODE) ? 0.8 : 0.0);
    private static final boolean HOLD_POSITION = booleanProperty(
            "ultima.clientBenchmark.holdPosition",
            "yaw_sweep".equals(CAMERA_MODE) || hasFixedPosition());
    private static final long[] FRAME_TIMES = new long[SAMPLE_FRAMES];

    private static int readyFrames;
    private static int samples;
    private static long frameStart;
    private static boolean complete;
    private static boolean cameraInitialized;
    private static double startX;
    private static double startY;
    private static double startZ;
    private static float startYaw;
    private static float startPitch;
    private static Pose sampleStartPose;
    private static Pose sampleEndPose;
    private static String sampleStartScreenshot;
    private static String sampleEndScreenshot;
    private static long terrainPrepareNsTotal;
    private static long terrainCommandNsTotal;
    private static long terrainOpaqueSubmitNsTotal;
    private static long terrainTranslucentSubmitNsTotal;
    private static long terrainTotalCpuNsTotal;
    private static long terrainSubmitNsTotal;
    private static long terrainDrawsTotal;
    private static long terrainVisibleSectionsTotal;
    private static long terrainSectionLayersTotal;
    private static long terrainUniformRecordsTotal;
    private static long terrainCommandRebuildsTotal;
    private static long terrainMetadataUpdatesTotal;
    private static boolean terrainRetainedActive;
    private static String terrainSubmitMode = "vanilla";
    private static long terrainMapCallsTotal;
    private static long terrainUnmapCallsTotal;
    private static long terrainWriteToBufferCallsTotal;
    private static long terrainWriteToBufferBytesTotal;
    private static long terrainMetadataBytesTotal;
    private static long terrainCommandBytesTotal;
    private static long terrainDirtyRangesTotal;
    private static long terrainMetadataDirtyRangesTotal;
    private static long terrainCommandDirtyRangesTotal;
    private static long terrainCommandRecordsChangedTotal;
    private static long terrainImmutableCommandWritesTotal;
    private static long terrainVisibilityCommandWritesTotal;
    private static long terrainBufferReallocsTotal;
    private static long terrainFenceWaitNsTotal;
    private static long terrainMapWaitNsTotal;
    private static long terrainRenderPassesTotal;
    private static long terrainEncodersTotal;
    private static long terrainHeaderWritesTotal;
    private static long terrainSectionTableSlotsWrittenTotal;
    private static long terrainGpuTerrainNsTotal;
    private static boolean terrainGpuTimingSupported;
    private static long terrainSubmitGroupCountTotal;
    private static long terrainTotalCommandRecordsTotal;
    private static long terrainLiveCommandRecordsTotal;
    private static long terrainHiddenCommandsTotal;
    private static long terrainLargestGroupCommandsTotal;
    private static long terrainCommandArrayCapacityTotal;
    private static long terrainCommandBufferBytesTotal;
    private static long terrainCommandBufferReallocsTotal;
    private static long terrainCommandArrayReallocsTotal;
    private static long terrainCommandsAddedTotal;
    private static long terrainCommandsRemovedTotal;
    private static long terrainVisibilityTogglesTotal;
    private static long terrainFailOpenFrames;
    private static long terrainPairingImbalanceFrames;

    private ClientFrameBenchmark() {
    }

    public static void beginFrame(final boolean worldReady) {
        if (!ENABLED || complete || !worldReady) {
            frameStart = 0L;
            return;
        }
        applyCamera();
        frameStart = System.nanoTime();
    }

    public static void endFrame() {
        long start = frameStart;
        frameStart = 0L;
        if (start == 0L || complete) {
            return;
        }

        long elapsed = System.nanoTime() - start;
        if (readyFrames++ < WARMUP_FRAMES) {
            if (readyFrames == WARMUP_FRAMES) {
                ClientOptimizationCounters.reset();
                TerrainFrameMetrics.resetLifetime();
                RetainedVisibilityDebug.reset();
                RetainedCompactionDebug.reset();
                if ("mesher_rebuild_storm".equals(SCENE) || "mesher_chunk_flight".equals(SCENE)) {
                    MesherMetrics.reset();
                }
            }
            return;
        }

        FRAME_TIMES[samples++] = elapsed;
        TerrainFrameMetrics.markSampledFrame();
        TerrainFrameMetrics.Snapshot terrain = TerrainFrameMetrics.snapshot(elapsed, 0L);
        terrainPrepareNsTotal += terrain.prepareNsAccum();
        terrainCommandNsTotal += terrain.commandNsAccum();
        terrainOpaqueSubmitNsTotal += terrain.opaqueSubmitNsAccum();
        terrainTranslucentSubmitNsTotal += terrain.translucentSubmitNsAccum();
        terrainTotalCpuNsTotal += terrain.totalCpuNsAccum();
        terrainSubmitNsTotal += terrain.submitNsAccum();
        terrainDrawsTotal += terrain.terrainDraws();
        terrainVisibleSectionsTotal += terrain.visibleSections();
        terrainSectionLayersTotal += terrain.visibleSectionLayers();
        terrainUniformRecordsTotal += terrain.uniformRecords();
        terrainCommandRebuildsTotal += terrain.commandRebuilds();
        terrainMetadataUpdatesTotal += terrain.metadataUpdates();
        terrainRetainedActive = terrain.retainedActive();
        terrainSubmitMode = terrain.submitMode();
        terrainMapCallsTotal += terrain.mapCalls();
        terrainUnmapCallsTotal += terrain.unmapCalls();
        terrainWriteToBufferCallsTotal += terrain.writeToBufferCalls();
        terrainWriteToBufferBytesTotal += terrain.writeToBufferBytes();
        terrainMetadataBytesTotal += terrain.metadataBytesWritten();
        terrainCommandBytesTotal += terrain.commandBytesWritten();
        terrainDirtyRangesTotal += terrain.metadataDirtyRanges() + terrain.commandDirtyRanges();
        terrainMetadataDirtyRangesTotal += terrain.metadataDirtyRanges();
        terrainCommandDirtyRangesTotal += terrain.commandDirtyRanges();
        terrainCommandRecordsChangedTotal += terrain.commandRecordsChanged();
        terrainImmutableCommandWritesTotal += terrain.immutableCommandWrites();
        terrainVisibilityCommandWritesTotal += terrain.visibilityCommandWrites();
        terrainBufferReallocsTotal += terrain.bufferReallocs();
        terrainFenceWaitNsTotal += terrain.fenceWaitNs();
        terrainMapWaitNsTotal += terrain.mapWaitNs();
        terrainRenderPassesTotal += terrain.renderPasses();
        terrainEncodersTotal += terrain.encoders();
        terrainHeaderWritesTotal += terrain.headerWrites();
        terrainSectionTableSlotsWrittenTotal += terrain.sectionTableSlotsWritten();
        terrainGpuTerrainNsTotal += terrain.gpuTerrainNs();
        terrainGpuTimingSupported = terrain.gpuTimingSupported();
        terrainSubmitGroupCountTotal += terrain.submitGroupCount();
        terrainTotalCommandRecordsTotal += terrain.totalCommandRecords();
        terrainLiveCommandRecordsTotal += terrain.liveCommandRecords();
        terrainHiddenCommandsTotal += terrain.hiddenZeroInstanceCommands();
        terrainLargestGroupCommandsTotal += terrain.largestSubmitGroupCommands();
        terrainCommandArrayCapacityTotal += terrain.commandArrayCapacity();
        terrainCommandBufferBytesTotal += terrain.commandBufferCapacityBytes();
        terrainCommandBufferReallocsTotal += terrain.commandBufferReallocs();
        terrainCommandArrayReallocsTotal += terrain.commandArrayReallocs();
        terrainCommandsAddedTotal += terrain.commandsAdded();
        terrainCommandsRemovedTotal += terrain.commandsRemoved();
        terrainVisibilityTogglesTotal += terrain.visibilityToggles();
        if (terrain.failOpenThisFrame()) {
            terrainFailOpenFrames++;
        }
        if (!terrain.timingPairingBalanced()) {
            terrainPairingImbalanceFrames++;
        }
        if (samples == 1) {
            sampleStartPose = capturePose();
            sampleStartScreenshot = captureScreenshot("sample_start", false);
        }
        if (samples == FRAME_TIMES.length) {
            complete = true;
            sampleEndPose = capturePose();
            sampleEndScreenshot = captureScreenshot("sample_end", true);
            boolean written = writeResults();
            if (written && EXIT_AFTER_WRITE) {
                LOGGER.info("Client benchmark JSON written; requesting Minecraft shutdown.");
                Minecraft.getInstance().stop();
            } else if (!written) {
                LOGGER.error("Client benchmark JSON was not written; leaving the client running.");
            }
        }
    }

    private static void applyCamera() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        if (!cameraInitialized) {
            startX = CAMERA_X != null ? CAMERA_X : player.getX();
            startY = CAMERA_Y != null ? CAMERA_Y : player.getY();
            startZ = CAMERA_Z != null ? CAMERA_Z : player.getZ();
            startYaw = CAMERA_YAW != null ? CAMERA_YAW.floatValue() : player.getYRot();
            startPitch = CAMERA_PITCH != null ? CAMERA_PITCH.floatValue() : player.getXRot();
            cameraInitialized = true;
        }

        boolean yawSweep = "yaw_sweep".equals(CAMERA_MODE);
        boolean chunkFlight = "chunk_flight".equals(CAMERA_MODE);
        if (!yawSweep && !chunkFlight && !HOLD_POSITION && CAMERA_YAW == null && CAMERA_PITCH == null) {
            return;
        }

        float yaw = startYaw + (float)(CAMERA_YAW_PER_FRAME * readyFrames);
        float pitch = startPitch;
        if (chunkFlight) {
            player.snapTo(startX, startY, startZ + CAMERA_Z_PER_FRAME * readyFrames, yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO);
        } else if (HOLD_POSITION || hasFixedPosition()) {
            player.snapTo(startX, startY, startZ, yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO);
        } else {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setOldPosAndRot();
        }
    }

    private static boolean writeResults() {
        long[] sorted = FRAME_TIMES.clone();
        Arrays.sort(sorted);
        long total = 0L;
        for (long frameTime : FRAME_TIMES) {
            total += frameTime;
        }

        double averageNs = (double)total / SAMPLE_FRAMES;
        double averageFps = 1_000_000_000.0 / averageNs;
        double medianNs = percentile(sorted, 0.5);
        double p95Ns = percentile(sorted, 0.95);
        double p99Ns = percentile(sorted, 0.99);
        double onePercentLowFps = 1_000_000_000.0 / slowestAverage(sorted, 0.01);
        double pointOnePercentLowFps = 1_000_000_000.0 / slowestAverage(sorted, 0.001);
        StringBuilder json = new StringBuilder(32_768);
        json.append("{\n");
        BenchmarkJson.field(json, "schemaVersion", 3);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "warmupFrames", WARMUP_FRAMES);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "sampleFrames", SAMPLE_FRAMES);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "averageFps", averageFps);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "medianFps", 1_000_000_000.0 / medianNs);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "onePercentLowFps", onePercentLowFps);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "pointOnePercentLowFps", pointOnePercentLowFps);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "averageFrameTimeMs", averageNs / 1_000_000.0);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "p95FrameTimeMs", p95Ns / 1_000_000.0);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "p99FrameTimeMs", p99Ns / 1_000_000.0);
        BenchmarkJson.comma(json);
        BenchmarkJson.field(json, "cpuFrameTimeAvailable", false);
        BenchmarkJson.comma(json);
        appendProtocol(json);
        BenchmarkJson.comma(json);
        appendEnvironment(json);
        BenchmarkJson.comma(json);
        appendModules(json);
        BenchmarkJson.comma(json);
        appendTerrainMetrics(json);
        BenchmarkJson.comma(json);
        appendTemporalMetrics(json);
        BenchmarkJson.comma(json);
        MesherMetrics.snapshot().appendJson(json);
        BenchmarkJson.comma(json);
        json.append("  \"frameTimesNs\": [");
        for (int i = 0; i < FRAME_TIMES.length; i++) {
            if (i != 0) {
                json.append(',');
            }
            json.append(FRAME_TIMES[i]);
        }
        json.append("]\n}\n");

        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);
            LOGGER.info(
                    "Client benchmark complete: {} frames, average {} FPS, 1% low {} FPS, 0.1% low {} FPS, output {}",
                    SAMPLE_FRAMES,
                    averageFps,
                    onePercentLowFps,
                    pointOnePercentLowFps,
                    OUTPUT.toAbsolutePath());
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.error("Could not write client benchmark output {}", OUTPUT.toAbsolutePath(), e);
            return false;
        }
    }

    private static void appendProtocol(final StringBuilder json) {
        String abRole = System.getProperty("ultima.clientBenchmark.abRole", "unspecified");
        json.append("  \"abProtocol\": {\n")
                .append("    \"primaryComparison\": \"disabled_vs_default\",\n")
                .append("    \"experimentalEnabledIsNotPrimary\": true,\n")
                .append("    \"minimumBalancedPairs\": 6,\n")
                .append("    \"requestedRole\": ").append(BenchmarkJson.quote(abRole)).append(",\n")
                .append("    \"scene\": ").append(BenchmarkJson.quote(System.getProperty("ultima.clientBenchmark.scene", "unspecified"))).append(",\n")
                .append("    \"pairLabel\": ").append(BenchmarkJson.quote(System.getProperty("ultima.clientBenchmark.pairLabel", ""))).append("\n")
                .append("  }");
    }

    private static void appendEnvironment(final StringBuilder json) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        GpuDevice device = RenderSystem.tryGetDevice();
        DeviceInfo deviceInfo = device == null ? null : device.getDeviceInfo();
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        String worldId = System.getProperty("ultima.clientBenchmark.worldId", "");
        String levelName = integrated != null ? integrated.getWorldData().getLevelName() : "";
        String dimension = minecraft.level != null ? minecraft.level.dimension().identifier().toString() : "";
        List<String> mods = loadedMods();
        List<String> resourcePacks = minecraft.getResourcePackRepository().getSelectedIds().stream().sorted().toList();
        boolean sodiumLoaded = dev.ultima.config.LoadedModCache.isLoaded("sodium");
        boolean irisLoaded = dev.ultima.config.LoadedModCache.isLoaded("iris");
        Pose requested = requestedPose();
        Pose actual = capturePose();

        json.append("  \"environment\": {\n")
                .append("    \"minecraft\": ").append(BenchmarkJson.quote(SharedConstants.getCurrentVersion().name())).append(",\n")
                .append("    \"minecraftId\": ").append(BenchmarkJson.quote(SharedConstants.getCurrentVersion().id())).append(",\n")
                .append("    \"fabricLoader\": ").append(BenchmarkJson.quote(modVersion("fabricloader"))).append(",\n")
                .append("    \"fabricApi\": ").append(BenchmarkJson.quote(modVersion("fabric-api"))).append(",\n")
                .append("    \"ultima\": ").append(BenchmarkJson.quote(modVersion("ultima"))).append(",\n")
                .append("    \"java\": ").append(BenchmarkJson.quote(System.getProperty("java.runtime.version", ""))).append(",\n")
                .append("    \"javaVm\": ").append(BenchmarkJson.quote(System.getProperty("java.vm.name", ""))).append(",\n")
                .append("    \"os\": ").append(BenchmarkJson.quote(System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""))).append(",\n")
                .append("    \"maxMemoryBytes\": ").append(Runtime.getRuntime().maxMemory()).append(",\n")
                .append("    \"lwjgl\": ").append(BenchmarkJson.quote(lwjglVersion())).append(",\n")
                .append("    \"gpuName\": ").append(BenchmarkJson.quote(deviceInfo != null ? deviceInfo.name() : "")).append(",\n")
                .append("    \"gpuVendor\": ").append(BenchmarkJson.quote(deviceInfo != null ? deviceInfo.vendorName() : "")).append(",\n")
                .append("    \"gpuDriver\": ").append(BenchmarkJson.quote(deviceInfo != null ? deviceInfo.driverInfo() : "")).append(",\n")
                .append("    \"gpuBackend\": ").append(BenchmarkJson.quote(deviceInfo != null ? deviceInfo.backendName() : "")).append(",\n")
                .append("    \"gpuType\": ").append(BenchmarkJson.quote(deviceInfo != null ? deviceInfo.type().toString() : "")).append(",\n")
                .append("    \"framebufferWidth\": ").append(minecraft.getWindow().getWidth()).append(",\n")
                .append("    \"framebufferHeight\": ").append(minecraft.getWindow().getHeight()).append(",\n")
                .append("    \"windowWidth\": ").append(minecraft.getWindow().getScreenWidth()).append(",\n")
                .append("    \"windowHeight\": ").append(minecraft.getWindow().getScreenHeight()).append(",\n")
                .append("    \"fullscreen\": ").append(options.fullscreen().get()).append(",\n")
                .append("    \"vsync\": ").append(options.enableVsync().get()).append(",\n")
                .append("    \"framerateLimit\": ").append(options.framerateLimit().get()).append(",\n")
                .append("    \"renderDistance\": ").append(options.renderDistance().get()).append(",\n")
                .append("    \"simulationDistance\": ").append(options.simulationDistance().get()).append(",\n")
                .append("    \"graphicsPreset\": ").append(BenchmarkJson.quote(options.graphicsPreset().get().getSerializedName())).append(",\n")
                .append("    \"ambientOcclusion\": ").append(options.ambientOcclusion().get()).append(",\n")
                .append("    \"biomeBlendRadius\": ").append(options.biomeBlendRadius().get()).append(",\n")
                .append("    \"entityDistanceScaling\": ").append(options.entityDistanceScaling().get()).append(",\n")
                .append("    \"entityShadows\": ").append(options.entityShadows().get()).append(",\n")
                .append("    \"mipmapLevels\": ").append(options.mipmapLevels().get()).append(",\n")
                .append("    \"particles\": ").append(BenchmarkJson.quote(options.particles().get().name())).append(",\n")
                .append("    \"clouds\": ").append(BenchmarkJson.quote(options.cloudStatus().get().name())).append(",\n")
                .append("    \"cloudRange\": ").append(options.cloudRange().get()).append(",\n")
                .append("    \"improvedTransparency\": ").append(options.improvedTransparency().get()).append(",\n")
                .append("    \"worldIdRequested\": ").append(BenchmarkJson.quote(worldId)).append(",\n")
                .append("    \"levelName\": ").append(BenchmarkJson.quote(levelName)).append(",\n")
                .append("    \"dimension\": ").append(BenchmarkJson.quote(dimension)).append(",\n")
                .append("    \"sodiumLoaded\": ").append(sodiumLoaded).append(",\n")
                .append("    \"irisLoaded\": ").append(irisLoaded).append(",\n")
                .append("    \"sodiumIrisRuntimeGateTested\": false,\n")
                .append("    \"shaderPackRuntimeTest\": ").append(BenchmarkJson.quote(irisLoaded ? "iris_present_pack_not_asserted" : "not_applicable_iris_absent")).append(",\n")
                .append("    \"mods\": ");
        appendQuotedArrayInline(json, mods);
        json.append(",\n    \"resourcePacks\": ");
        appendQuotedArrayInline(json, resourcePacks);
        json.append(",\n    \"camera\": {\n")
                .append("      \"mode\": ").append(BenchmarkJson.quote(CAMERA_MODE)).append(",\n")
                .append("      \"holdPosition\": ").append(HOLD_POSITION).append(",\n")
                .append("      \"yawDegreesPerFrame\": ").append(CAMERA_YAW_PER_FRAME).append(",\n")
                .append("      \"zPerFrame\": ").append(CAMERA_Z_PER_FRAME).append(",\n")
                .append("      \"requested\": ");
        appendPose(json, requested);
        json.append(",\n      \"actual\": ");
        appendPose(json, actual);
        json.append(",\n      \"sampleStart\": ");
        appendPose(json, sampleStartPose);
        json.append(",\n      \"sampleEnd\": ");
        appendPose(json, sampleEndPose);
        json.append("\n    },\n")
                .append("    \"screenshots\": {\n")
                .append("      \"requested\": ").append(CAPTURE_SCREENSHOTS).append(",\n")
                .append("      \"sampleStart\": ").append(BenchmarkJson.quote(sampleStartScreenshot)).append(",\n")
                .append("      \"sampleEnd\": ").append(BenchmarkJson.quote(sampleEndScreenshot)).append("\n")
                .append("    }\n")
                .append("  }");
    }

    private static void appendModules(final StringBuilder json) {
        json.append("  \"modules\": [\n");
        List<ResolvedModule> modules = UltimaConfig.get().resolvedModules();
        for (int i = 0; i < modules.size(); i++) {
            ResolvedModule module = modules.get(i);
            json.append("    {\n")
                    .append("      \"key\": ").append(BenchmarkJson.quote(module.key())).append(",\n")
                    .append("      \"requested\": ").append(module.requested()).append(",\n")
                    .append("      \"enabled\": ").append(module.enabled()).append(",\n")
                    .append("      \"enabledByDefault\": ").append(module.enabledByDefault()).append(",\n")
                    .append("      \"clientOnly\": ").append(module.clientOnly()).append(",\n")
                    .append("      \"reason\": ").append(BenchmarkJson.quote(module.reason())).append(",\n")
                    .append("      \"detail\": ").append(BenchmarkJson.quote(module.detail())).append(",\n")
                    .append("      \"blockingDependency\": ").append(BenchmarkJson.quote(module.blockingDependency())).append(",\n");
            json.append("      \"dependencies\": ");
            appendQuotedArrayInline(json, module.dependencies());
            json.append(",\n      \"incompatibleMods\": ");
            appendQuotedArrayInline(json, module.incompatibleMods());
            json.append(",\n      \"loadedIncompatibleMods\": ");
            appendQuotedArrayInline(json, module.loadedIncompatibleMods());
            json.append(",\n      \"moduleClass\": ").append(BenchmarkJson.quote(module.moduleClass()));
            json.append("\n    }");
            if (i + 1 < modules.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void appendTerrainMetrics(final StringBuilder json) {
        int n = Math.max(1, SAMPLE_FRAMES);
        TerrainFrameMetrics.Snapshot last = TerrainFrameMetrics.snapshot(0L, 0L);
        json.append("  \"terrainMetrics\": {\n")
                .append("    \"timingModel\": \"ultima_stage1_symmetric_v1\",\n")
                .append("    \"terrainPrepareCpuNsAvg\": ").append((double)terrainPrepareNsTotal / n).append(",\n")
                .append("    \"terrainOpaqueSubmitCpuNsAvg\": ").append((double)terrainOpaqueSubmitNsTotal / n).append(",\n")
                .append("    \"terrainTranslucentSubmitCpuNsAvg\": ").append((double)terrainTranslucentSubmitNsTotal / n).append(",\n")
                .append("    \"terrainCommandCpuNsAvg\": ").append((double)terrainCommandNsTotal / n).append(",\n")
                .append("    \"terrainTotalCpuNsAvg\": ").append((double)terrainTotalCpuNsTotal / n).append(",\n")
                .append("    \"prepareNsAvg\": ").append((double)terrainPrepareNsTotal / n).append(",\n")
                .append("    \"commandNsAvg\": ").append((double)terrainCommandNsTotal / n).append(",\n")
                .append("    \"submitNsAvg\": ").append((double)terrainSubmitNsTotal / n).append(",\n")
                .append("    \"terrainDrawsAvg\": ").append((double)terrainDrawsTotal / n).append(",\n")
                .append("    \"visibleSectionsAvg\": ").append((double)terrainVisibleSectionsTotal / n).append(",\n")
                .append("    \"visibleSectionLayersAvg\": ").append((double)terrainSectionLayersTotal / n).append(",\n")
                .append("    \"uniformRecordsAvg\": ").append((double)terrainUniformRecordsTotal / n).append(",\n")
                .append("    \"commandRebuildsAvg\": ").append((double)terrainCommandRebuildsTotal / n).append(",\n")
                .append("    \"metadataUpdatesAvg\": ").append((double)terrainMetadataUpdatesTotal / n).append(",\n")
                .append("    \"chunkRebuilds\": ").append(last.chunkRebuilds()).append(",\n")
                .append("    \"chunkUploads\": ").append(last.chunkUploads()).append(",\n")
                .append("    \"gpuFrameNs\": ").append(last.gpuFrameNs()).append(",\n")
                .append("    \"retainedActive\": ").append(terrainRetainedActive).append(",\n")
                .append("    \"commandBatchesReused\": ").append(last.commandBatchesReused()).append(",\n")
                .append("    \"submitMode\": ").append(BenchmarkJson.quote(terrainSubmitMode)).append(",\n")
                .append("    \"timingPairingBalancedFrames\": ").append(SAMPLE_FRAMES - terrainPairingImbalanceFrames).append(",\n")
                .append("    \"failOpenFrames\": ").append(terrainFailOpenFrames).append(",\n")
                .append("    \"syncCountersScope\": \"ultima_issued_only\",\n")
                .append("    \"driverImplicitSyncObserved\": false,\n")
                .append("    \"writeToBufferMayInsertBackendBarriers\": true,\n")
                .append("    \"ultimaIssuedMapCallsAvg\": ").append((double)terrainMapCallsTotal / n).append(",\n")
                .append("    \"ultimaIssuedUnmapCallsAvg\": ").append((double)terrainUnmapCallsTotal / n).append(",\n")
                .append("    \"ultimaIssuedFenceWaitNsAvg\": ").append((double)terrainFenceWaitNsTotal / n).append(",\n")
                .append("    \"ultimaIssuedMapWaitNsAvg\": ").append((double)terrainMapWaitNsTotal / n).append(",\n")
                .append("    \"writeToBufferCallsAvg\": ").append((double)terrainWriteToBufferCallsTotal / n).append(",\n")
                .append("    \"writeToBufferBytesAvg\": ").append((double)terrainWriteToBufferBytesTotal / n).append(",\n")
                .append("    \"metadataBytesWrittenAvg\": ").append((double)terrainMetadataBytesTotal / n).append(",\n")
                .append("    \"commandBytesWrittenAvg\": ").append((double)terrainCommandBytesTotal / n).append(",\n")
                .append("    \"metadataDirtyRangesAvg\": ").append((double)terrainMetadataDirtyRangesTotal / n).append(",\n")
                .append("    \"commandDirtyRangesAvg\": ").append((double)terrainCommandDirtyRangesTotal / n).append(",\n")
                .append("    \"commandRecordsChangedAvg\": ").append((double)terrainCommandRecordsChangedTotal / n).append(",\n")
                .append("    \"immutableCommandWritesAvg\": ").append((double)terrainImmutableCommandWritesTotal / n).append(",\n")
                .append("    \"visibilityCommandWritesAvg\": ").append((double)terrainVisibilityCommandWritesTotal / n).append(",\n")
                .append("    \"bufferReallocsAvg\": ").append((double)terrainBufferReallocsTotal / n).append(",\n")
                .append("    \"renderPassesAvg\": ").append((double)terrainRenderPassesTotal / n).append(",\n")
                .append("    \"encodersAvg\": ").append((double)terrainEncodersTotal / n).append(",\n")
                .append("    \"headerWritesAvg\": ").append((double)terrainHeaderWritesTotal / n).append(",\n")
                .append("    \"sectionTableSlotsWrittenAvg\": ").append((double)terrainSectionTableSlotsWrittenAvg()).append(",\n")
                .append("    \"gpuTerrainNsAvg\": ").append((double)terrainGpuTerrainNsTotal / n).append(",\n")
                .append("    \"gpuTimingSupported\": ").append(terrainGpuTimingSupported).append(",\n")
                .append("    \"submitGroupCountAvg\": ").append((double)terrainSubmitGroupCountTotal / n).append(",\n")
                .append("    \"totalCommandRecordsAvg\": ").append((double)terrainTotalCommandRecordsTotal / n).append(",\n")
                .append("    \"liveCommandRecordsAvg\": ").append((double)terrainLiveCommandRecordsTotal / n).append(",\n")
                .append("    \"hiddenZeroInstanceCommandsAvg\": ").append((double)terrainHiddenCommandsTotal / n).append(",\n")
                .append("    \"liveToTotalRatioAvg\": ").append(liveToTotalAvg()).append(",\n")
                .append("    \"largestSubmitGroupCommandsAvg\": ").append((double)terrainLargestGroupCommandsTotal / n).append(",\n")
                .append("    \"commandArrayCapacityAvg\": ").append((double)terrainCommandArrayCapacityTotal / n).append(",\n")
                .append("    \"commandBufferCapacityBytesAvg\": ").append((double)terrainCommandBufferBytesTotal / n).append(",\n")
                .append("    \"commandBufferReallocsAvg\": ").append((double)terrainCommandBufferReallocsTotal / n).append(",\n")
                .append("    \"commandArrayReallocsAvg\": ").append((double)terrainCommandArrayReallocsTotal / n).append(",\n")
                .append("    \"commandsAddedAvg\": ").append((double)terrainCommandsAddedTotal / n).append(",\n")
                .append("    \"commandsRemovedAvg\": ").append((double)terrainCommandsRemovedTotal / n).append(",\n")
                .append("    \"visibilityTogglesAvg\": ").append((double)terrainVisibilityTogglesTotal / n).append(",\n")
                .append("    \"maxTotalCommandRecords\": ").append(last.maxTotalCommandRecords()).append(",\n")
                .append("    \"maxHiddenCommands\": ").append(last.maxHiddenCommands()).append(",\n")
                .append("    \"minLiveRatio\": ").append(last.minLiveRatio()).append(",\n")
                .append("    \"firstSampleTotalCommands\": ").append(last.firstSampleTotalCommands()).append(",\n")
                .append("    \"firstSampleLiveCommands\": ").append(last.firstSampleLiveCommands()).append(",\n")
                .append("    \"lastSampleTotalCommands\": ").append(last.lastSampleTotalCommands()).append(",\n")
                .append("    \"lastSampleLiveCommands\": ").append(last.lastSampleLiveCommands()).append(",\n")
                .append("    \"commandPopulationGrewWhileLiveBounded\": ").append(commandPopulationGrew(last)).append(",\n")
                .append("    \"a2VisibilityDebugEnabled\": ").append(RetainedVisibilityDebug.ENABLED).append(",\n")
                .append("    \"a2SameFrameReentries\": ").append(RetainedVisibilityDebug.sameFrameReentries()).append(",\n")
                .append("    \"a2OneFrameLateReentries\": ").append(RetainedVisibilityDebug.oneFrameLateReentries()).append(",\n")
                .append("    \"compactionDebugEnabled\": ").append(RetainedCompactionDebug.ENABLED).append(",\n")
                .append("    \"successfulCompactions\": ").append(RetainedCompactionDebug.successfulCompactions()).append("\n")
                .append("  }");
    }

    private static double terrainSectionTableSlotsWrittenAvg() {
        return (double)terrainSectionTableSlotsWrittenTotal / Math.max(1, SAMPLE_FRAMES);
    }

    private static double liveToTotalAvg() {
        return terrainTotalCommandRecordsTotal == 0L
                ? 1.0
                : (double)terrainLiveCommandRecordsTotal / (double)terrainTotalCommandRecordsTotal;
    }

    private static boolean commandPopulationGrew(final TerrainFrameMetrics.Snapshot last) {
        int first = last.firstSampleTotalCommands();
        int lastTotal = last.lastSampleTotalCommands();
        int firstLive = last.firstSampleLiveCommands();
        int lastLive = last.lastSampleLiveCommands();
        if (first < 0 || lastTotal <= first) {
            return false;
        }
        int liveDelta = Math.abs(lastLive - Math.max(0, firstLive));
        return lastTotal > first && liveDelta * 4 < (lastTotal - first);
    }

    private static void appendTemporalMetrics(final StringBuilder json) {
        boolean enabled = UltimaConfig.get().isEnabled("temporal");
        TemporalPipeline pipeline = TemporalPipeline.get();
        var frame = pipeline.frame();
        json.append("  \"temporalMetrics\": {\n")
                .append("    \"moduleEnabled\": ").append(enabled).append(",\n")
                .append("    \"requestedMode\": ").append(BenchmarkJson.quote(pipeline.settings().requested().name())).append(",\n")
                .append("    \"resolvedMode\": ").append(BenchmarkJson.quote(pipeline.settings().resolved().name())).append(",\n")
                .append("    \"requestedUnsupported\": ").append(pipeline.settings().requestedUnsupported()).append(",\n")
                .append("    \"nativePassthrough\": ").append(pipeline.settings().resolved().isNative()).append(",\n")
                .append("    \"renderWidth\": ").append(frame.renderWidth).append(",\n")
                .append("    \"renderHeight\": ").append(frame.renderHeight).append(",\n")
                .append("    \"outputWidth\": ").append(frame.outputWidth).append(",\n")
                .append("    \"outputHeight\": ").append(frame.outputHeight).append(",\n")
                .append("    \"renderEqualsOutput\": ").append(
                        frame.renderWidth == frame.outputWidth && frame.renderHeight == frame.outputHeight).append(",\n")
                .append("    \"jitterX\": ").append(frame.currentJitterX).append(",\n")
                .append("    \"jitterY\": ").append(frame.currentJitterY).append(",\n")
                .append("    \"frameIndex\": ").append(frame.frameIndex).append(",\n")
                .append("    \"resetCount\": ").append(pipeline.resetCount()).append(",\n")
                .append("    \"lastResetReason\": ").append(BenchmarkJson.quote(
                        pipeline.lastResetReason() == null ? "" : pipeline.lastResetReason().name())).append(",\n")
                .append("    \"motionVectorPlan\": ").append(BenchmarkJson.quote(frame.motionVectorPlan.name())).append(",\n")
                .append("    \"evaluatedThisFrame\": ").append(frame.evaluatedThisFrame).append(",\n")
                .append("    \"failedOpen\": ").append(pipeline.isFailedOpen()).append("\n")
                .append("  }");
    }

    private static void appendQuotedArrayInline(final StringBuilder json, final Iterable<String> values) {
        json.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append(BenchmarkJson.quote(value));
        }
        json.append(']');
    }

    private static void appendPose(final StringBuilder json, final Pose pose) {
        if (pose == null) {
            json.append("null");
            return;
        }
        json.append("{")
                .append("\"x\": ").append(pose.x)
                .append(", \"y\": ").append(pose.y)
                .append(", \"z\": ").append(pose.z)
                .append(", \"yaw\": ").append(pose.yaw)
                .append(", \"pitch\": ").append(pose.pitch)
                .append('}');
    }

    private static List<String> loadedMods() {
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            mods.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
        }
        mods.sort(Comparator.naturalOrder());
        return mods;
    }

    private static String modVersion(final String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    private static String lwjglVersion() {
        try {
            return org.lwjgl.Version.getVersion();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Pose requestedPose() {
        return new Pose(
                CAMERA_X != null ? CAMERA_X : Double.NaN,
                CAMERA_Y != null ? CAMERA_Y : Double.NaN,
                CAMERA_Z != null ? CAMERA_Z : Double.NaN,
                CAMERA_YAW != null ? CAMERA_YAW : Double.NaN,
                CAMERA_PITCH != null ? CAMERA_PITCH : Double.NaN);
    }

    private static Pose capturePose() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return null;
        }
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static String captureScreenshot(final String suffix, final boolean wait) {
        if (!CAPTURE_SCREENSHOTS) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String fileName = SCREENSHOT_PREFIX + "-" + suffix + ".png";
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Screenshot.grab(
                    minecraft.gameDirectory,
                    fileName,
                    minecraft.gameRenderer.mainRenderTarget(),
                    1,
                    message -> latch.countDown());
            if (wait && !latch.await(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Timed out waiting for screenshot {}", fileName);
            }
            return new java.io.File(minecraft.gameDirectory, "screenshots/" + fileName).getAbsolutePath();
        } catch (Exception e) {
            LOGGER.warn("Could not capture benchmark screenshot {}", fileName, e);
            return null;
        }
    }

    private static double percentile(final long[] sorted, final double percentile) {
        int index = Math.max(0, (int)Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static double slowestAverage(final long[] sorted, final double fraction) {
        int count = Math.max(1, (int)Math.ceil(sorted.length * fraction));
        long total = 0L;
        for (int i = sorted.length - count; i < sorted.length; i++) {
            total += sorted[i];
        }
        return (double)total / count;
    }

    private static int defaultWarmupFrames(final String scene) {
        return warmupFramesForScene(scene);
    }

    private static int defaultSampleFrames(final String scene) {
        return sampleFramesForScene(scene);
    }

    /**
     * Default warmup frames for a mesher hardware scene id. Tests use this instead of grepping
     * this file for scene identifiers.
     */
    public static int warmupFramesForScene(final String scene) {
        return switch (scene) {
            case "mesher_cold_load" -> 60;
            case "mesher_rebuild_storm" -> 600;
            default -> 1200;
        };
    }

    /**
     * Default sample frames for a mesher hardware scene id.
     */
    public static int sampleFramesForScene(final String scene) {
        return switch (scene) {
            case "mesher_cold_load" -> 2400;
            default -> 12000;
        };
    }

    /**
     * Default camera mode for a mesher hardware scene id.
     */
    public static String cameraModeForScene(final String scene) {
        return "mesher_chunk_flight".equals(scene) ? "chunk_flight" : "stationary";
    }

    private static int positiveIntegerProperty(final String key, final int defaultValue) {
        int value = Integer.getInteger(key, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private static boolean booleanProperty(final String key, final boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    private static Double doubleProperty(final String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring invalid double property {}={}", key, value);
            return null;
        }
    }

    private static double doubleProperty(final String key, final double defaultValue) {
        Double value = doubleProperty(key);
        return value != null ? value : defaultValue;
    }

    private static boolean hasFixedPosition() {
        return CAMERA_X != null && CAMERA_Y != null && CAMERA_Z != null;
    }

    private record Pose(double x, double y, double z, double yaw, double pitch) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.4f,%.4f,%.4f yaw=%.3f pitch=%.3f", x, y, z, yaw, pitch);
        }
    }
}
