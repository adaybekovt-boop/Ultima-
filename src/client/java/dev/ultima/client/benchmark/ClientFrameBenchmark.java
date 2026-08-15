package dev.ultima.client.benchmark;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaConfig.ResolvedModule;
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
    private static final int WARMUP_FRAMES = positiveIntegerProperty("ultima.clientBenchmark.warmupFrames", 1200);
    private static final int SAMPLE_FRAMES = positiveIntegerProperty("ultima.clientBenchmark.sampleFrames", 12000);
    private static final Path OUTPUT = Path.of(
            System.getProperty("ultima.clientBenchmark.output", "run/ultima-client-benchmark.json"));
    private static final String SCREENSHOT_PREFIX = System.getProperty(
            "ultima.clientBenchmark.screenshotPrefix", "ultima-client-benchmark");
    private static final String CAMERA_MODE = System.getProperty("ultima.clientBenchmark.cameraMode", "stationary");
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
    private static long terrainSubmitNsTotal;
    private static long terrainDrawsTotal;
    private static long terrainVisibleSectionsTotal;
    private static long terrainSectionLayersTotal;
    private static long terrainUniformRecordsTotal;
    private static long terrainCommandRebuildsTotal;
    private static long terrainMetadataUpdatesTotal;
    private static boolean terrainRetainedActive;
    private static String terrainSubmitMode = "vanilla";

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
            }
            return;
        }

        FRAME_TIMES[samples++] = elapsed;
        TerrainFrameMetrics.Snapshot terrain = TerrainFrameMetrics.snapshot(elapsed, 0L);
        terrainPrepareNsTotal += terrain.prepareNsAccum();
        terrainCommandNsTotal += terrain.commandNsAccum();
        terrainSubmitNsTotal += terrain.submitNsAccum();
        terrainDrawsTotal += terrain.terrainDraws();
        terrainVisibleSectionsTotal += terrain.visibleSections();
        terrainSectionLayersTotal += terrain.visibleSectionLayers();
        terrainUniformRecordsTotal += terrain.uniformRecords();
        terrainCommandRebuildsTotal += terrain.commandRebuilds();
        terrainMetadataUpdatesTotal += terrain.metadataUpdates();
        terrainRetainedActive = terrain.retainedActive();
        terrainSubmitMode = terrain.submitMode();
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
        BenchmarkJson.field(json, "schemaVersion", 2);
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
        boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        boolean irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
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
                .append("    \"submitMode\": ").append(BenchmarkJson.quote(terrainSubmitMode)).append("\n")
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
