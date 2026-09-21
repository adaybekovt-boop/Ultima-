package dev.ultima.client.iris.cache;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.cache.iris.ArtifactCacheMetrics;
import dev.ultima.cache.iris.ArtifactCacheStore;
import dev.ultima.cache.iris.ArtifactKey;
import dev.ultima.cache.iris.ShaderArtifact;
import dev.ultima.config.KillerModuleCompatibility;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime bridge around Iris' private CPU transform boundary. */
public final class IrisFrontendArtifactCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-iris-artifact-cache");
    private static final boolean VERIFY = Boolean.getBoolean("ultima.irisShaderFrontendArtifactCache.verify");
    private static final long MAX_BYTES = positiveLongProperty(
            "ultima.irisShaderFrontendArtifactCache.maxBytes", 256L * 1024L * 1024L);
    private static final int MAX_ENTRIES = positiveIntegerProperty(
            "ultima.irisShaderFrontendArtifactCache.maxEntries", 2_048);
    private static final ArtifactCacheMetrics METRICS = new ArtifactCacheMetrics();
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final ThreadLocal<Long> RELOAD_STARTED = new ThreadLocal<>();

    private static volatile ArtifactCacheStore store;
    private static volatile boolean failedOpen;
    private static volatile String failureReason = "";
    private static volatile Method getIrisConfig;
    private static volatile Method debugOptionsEnabled;
    private static volatile Class<? extends Enum<?>> patchShaderType;

    private IrisFrontendArtifactCache() {
    }

    public static @org.jspecify.annotations.Nullable Object beginGraphics(
            final String name,
            final String vertex,
            final String geometry,
            final String tessControl,
            final String tessEval,
            final String fragment,
            final Object parameters) {
        return begin(
                "graphics",
                name,
                List.of(
                        new IrisTransformKeyEncoder.StageSource("VERTEX", vertex),
                        new IrisTransformKeyEncoder.StageSource("GEOMETRY", geometry),
                        new IrisTransformKeyEncoder.StageSource("TESS_CONTROL", tessControl),
                        new IrisTransformKeyEncoder.StageSource("TESS_EVAL", tessEval),
                        new IrisTransformKeyEncoder.StageSource("FRAGMENT", fragment)),
                parameters);
    }

    public static @org.jspecify.annotations.Nullable Object beginCompute(
            final String name, final String compute, final Object parameters) {
        return begin(
                "compute",
                name,
                List.of(new IrisTransformKeyEncoder.StageSource("COMPUTE", compute)),
                parameters);
    }

    /** Called at the normal Iris return point. A cache hit marks the pending call as completed. */
    public static void finish(final @org.jspecify.annotations.Nullable Object transformed) {
        try {
            finishInternal(transformed);
        } catch (Throwable throwable) {
            disable("finish_failure:" + throwable.getClass().getSimpleName());
        }
    }

    private static void finishInternal(final @org.jspecify.annotations.Nullable Object transformed) {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null || pending.servedHit()) {
            return;
        }

        long transformNanos = Math.max(0L, System.nanoTime() - pending.startedNanos());
        METRICS.recordFrontendTransform(transformNanos);
        Map<String, String> stages = extractStages(transformed);
        if (stages == null) {
            return;
        }

        if (pending.verifyArtifact() != null) {
            if (pending.verifyArtifact().stages().equals(stages)) {
                METRICS.recordVerifyMatch();
            } else {
                METRICS.recordVerifyMismatch();
                pending.store().invalidate(pending.key());
                disable("verify_mismatch");
            }
            return;
        }

        pending.store().write(pending.key(), new ShaderArtifact(stages, transformNanos));
    }

    public static void clearThreadState() {
        PENDING.remove();
    }

    public static void beginReload() {
        PENDING.remove();
        RELOAD_STARTED.set(System.nanoTime());
    }

    public static void endReload() {
        Long started = RELOAD_STARTED.get();
        RELOAD_STARTED.remove();
        PENDING.remove();
        if (started != null) {
            METRICS.recordReload(System.nanoTime() - started);
        }
    }

    public static ArtifactCacheMetrics.Snapshot snapshot() {
        ArtifactCacheStore current = store;
        return current == null ? METRICS.snapshot(0L, 0) : current.snapshot();
    }

    public static boolean verifyMode() {
        return VERIFY;
    }

    public static boolean failedOpen() {
        return failedOpen;
    }

    public static String failureReason() {
        return failureReason;
    }

    private static @org.jspecify.annotations.Nullable Object begin(
            final String kind,
            final String name,
            final List<IrisTransformKeyEncoder.StageSource> sources,
            final Object parameters) {
        PENDING.remove();
        if (failedOpen || allSourcesNull(sources)) {
            return null;
        }

        try {
            KillerModuleCompatibility.AdapterState adapter =
                    KillerModuleCompatibility.state(KillerModuleCompatibility.IRIS_MODULE);
            if (!adapter.supported()) {
                disable("adapter_inactive:" + adapter.state());
                return null;
            }
            if (!supportedBackend()) {
                disable("unsupported_gpu_backend");
                return null;
            }

            IrisTransformKeyEncoder.Environment environment = new IrisTransformKeyEncoder.Environment(
                    adapter.state(),
                    adapter.fingerprint(),
                    adapter.version(),
                    modVersion("minecraft"),
                    irisDebugOptionsEnabled(),
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            ArtifactKey key = IrisTransformKeyEncoder.encode(kind, name, sources, parameters, environment);
            if (key == null) {
                METRICS.recordUnkeyableRequest();
                return null;
            }

            ArtifactCacheStore current = store();
            long started = System.nanoTime();
            Optional<ShaderArtifact> cached = current.read(key);
            if (cached.isPresent()) {
                Object result = materialize(cached.get());
                if (result == null) {
                    current.invalidate(key);
                    PENDING.set(new Pending(key, current, started, null, false));
                    return null;
                }
                if (!VERIFY) {
                    PENDING.set(new Pending(key, current, started, null, true));
                    return result;
                }
                PENDING.set(new Pending(key, current, started, cached.get(), false));
                return null;
            }
            PENDING.set(new Pending(key, current, started, null, false));
            return null;
        } catch (Throwable throwable) {
            disable("runtime_failure:" + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static ArtifactCacheStore store() {
        ArtifactCacheStore local = store;
        if (local == null) {
            synchronized (IrisFrontendArtifactCache.class) {
                local = store;
                if (local == null) {
                    Path directory = FabricLoader.getInstance()
                            .getGameDir()
                            .resolve("cache")
                            .resolve("ultima")
                            .resolve("iris-frontend-v1");
                    local = new ArtifactCacheStore(directory, MAX_BYTES, MAX_ENTRIES, METRICS);
                    store = local;
                }
            }
        }
        return local;
    }

    private static boolean supportedBackend() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null || device.getDeviceInfo() == null) {
            return false;
        }
        String backend = device.getDeviceInfo().backendName();
        return backend != null && backend.toLowerCase(java.util.Locale.ROOT).contains("opengl");
    }

    private static boolean irisDebugOptionsEnabled() throws ReflectiveOperationException {
        Method getConfig = getIrisConfig;
        Method getDebug = debugOptionsEnabled;
        if (getConfig == null || getDebug == null) {
            synchronized (IrisFrontendArtifactCache.class) {
                getConfig = getIrisConfig;
                getDebug = debugOptionsEnabled;
                if (getConfig == null || getDebug == null) {
                    Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false,
                            IrisFrontendArtifactCache.class.getClassLoader());
                    getConfig = iris.getMethod("getIrisConfig");
                    Class<?> config = Class.forName("net.irisshaders.iris.config.IrisConfig", false,
                            IrisFrontendArtifactCache.class.getClassLoader());
                    getDebug = config.getMethod("areDebugOptionsEnabled");
                    getIrisConfig = getConfig;
                    debugOptionsEnabled = getDebug;
                }
            }
        }
        Object config = getConfig.invoke(null);
        return Boolean.TRUE.equals(getDebug.invoke(config));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @org.jspecify.annotations.Nullable Object materialize(final ShaderArtifact artifact) {
        try {
            Class<? extends Enum<?>> enumClass = patchShaderType;
            if (enumClass == null) {
                Class<?> loaded = Class.forName(
                        "net.irisshaders.iris.pipeline.transform.PatchShaderType",
                        false,
                        IrisFrontendArtifactCache.class.getClassLoader());
                if (!loaded.isEnum()) {
                    return null;
                }
                enumClass = (Class<? extends Enum<?>>)loaded;
                patchShaderType = enumClass;
            }
            Map<Object, String> transformed = new LinkedHashMap<>();
            for (Map.Entry<String, String> stage : artifact.stages().entrySet()) {
                Object key = Enum.valueOf((Class)enumClass, stage.getKey());
                transformed.put(key, stage.getValue());
            }
            return transformed;
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static @org.jspecify.annotations.Nullable Map<String, String> extractStages(
            final @org.jspecify.annotations.Nullable Object transformed) {
        if (!(transformed instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> stages = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof Enum<?> stage) || !(entry.getValue() instanceof String source)) {
                return null;
            }
            if (stages.put(stage.name(), source) != null) {
                return null;
            }
        }
        return Map.copyOf(stages);
    }

    private static boolean allSourcesNull(final List<IrisTransformKeyEncoder.StageSource> sources) {
        for (IrisTransformKeyEncoder.StageSource source : sources) {
            if (source.source() != null) {
                return false;
            }
        }
        return true;
    }

    private static String modVersion(final String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    private static void disable(final String reason) {
        if (!failedOpen) {
            failureReason = reason;
            failedOpen = true;
            LOGGER.warn("Iris frontend artifact cache disabled for this process: {}", reason);
        }
        PENDING.remove();
    }

    private static long positiveLongProperty(final String key, final long defaultValue) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : defaultValue;
    }

    private static int positiveIntegerProperty(final String key, final int defaultValue) {
        int value = Integer.getInteger(key, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private record Pending(
            ArtifactKey key,
            ArtifactCacheStore store,
            long startedNanos,
            @org.jspecify.annotations.Nullable ShaderArtifact verifyArtifact,
            boolean servedHit) {
    }
}
