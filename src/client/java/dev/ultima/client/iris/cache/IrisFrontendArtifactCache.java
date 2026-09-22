package dev.ultima.client.iris.cache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent cache behind Iris 1.11.4's own process-local transform LRU.
 *
 * <p>Iris checks {@code TransformPatcher.cache} before calling {@code transformInternal}. This
 * bridge runs only at that internal call, so a same-JVM repeat is served by Iris and does not
 * touch Ultima or disk. Disk is the restart path. There is no second large Ultima shader cache.
 */
public final class IrisFrontendArtifactCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-iris-artifact-cache");
    private static final boolean VERIFY = Boolean.getBoolean("ultima.irisShaderFrontendArtifactCache.verify");
    private static final long MAX_BYTES = positiveLongProperty(
            "ultima.irisShaderFrontendArtifactCache.maxBytes", 256L * 1024L * 1024L);
    private static final int MAX_ENTRIES = positiveIntegerProperty(
            "ultima.irisShaderFrontendArtifactCache.maxEntries", 2_048);
    private static final ArtifactCacheMetrics METRICS = new ArtifactCacheMetrics();
    private static final ThreadLocal<Long> RELOAD_STARTED = new ThreadLocal<>();

    private static volatile ArtifactCacheStore store;
    private static volatile boolean failedOpen;
    private static volatile String failureReason = "";
    private static volatile Method getIrisConfig;
    private static volatile Method debugOptionsEnabled;
    private static volatile Class<? extends Enum<?>> patchShaderType;
    private static volatile boolean contractProven;
    private static volatile boolean contractRejected;

    private IrisFrontendArtifactCache() {
    }

    /**
     * @param original Iris {@code transformInternal}; called only on a persistent miss or in verify mode
     */
    public static Map<?, ?> aroundTransform(
            final String name,
            final Map<?, ?> sources,
            final Object parameters,
            final Operation<Map<?, ?>> original) {
        if (failedOpen || contractRejected) {
            METRICS.noteMiss(failedOpen ? "failed_open" : "contract_rejected");
            return original.call(name, sources, parameters);
        }
        Map<?, ?> computed = null;
        try {
            List<IrisTransformKeyEncoder.StageSource> stages = stageSources(sources);
            if (stages == null) {
                METRICS.noteMiss("unreadable_sources");
                return original.call(name, sources, parameters);
            }
            if (allSourcesNull(stages)) {
                METRICS.noteMiss("all_stages_absent");
                return original.call(name, sources, parameters);
            }
            KillerModuleCompatibility.AdapterState adapter =
                    KillerModuleCompatibility.state(KillerModuleCompatibility.IRIS_MODULE);
            if (!adapter.supported()) {
                disable("adapter_inactive:" + adapter.state());
                METRICS.noteMiss("adapter_inactive");
                return original.call(name, sources, parameters);
            }
            if (!supportedBackend()) {
                disable("unsupported_gpu_backend");
                METRICS.noteMiss("unsupported_gpu_backend");
                return original.call(name, sources, parameters);
            }
            if (!IrisTransformKeyEncoder.supportedStructure(parameters)) {
                if (parameters != null
                        && parameters.getClass().getName().startsWith(
                                "net.irisshaders.iris.pipeline.transform.parameter.")) {
                    contractRejected = true;
                    disable("unsupported_parameter_schema");
                    METRICS.noteMiss("unsupported_parameter_schema");
                } else {
                    METRICS.noteMiss("non_parameter_object");
                }
                return original.call(name, sources, parameters);
            }

            String kind = stages.size() == 1 && "COMPUTE".equals(stages.get(0).stage()) ? "compute" : "graphics";
            IrisTransformKeyEncoder.Environment environment = new IrisTransformKeyEncoder.Environment(
                    adapter.state(),
                    adapter.fingerprint(),
                    adapter.version(),
                    modVersion("minecraft"),
                    irisDebugOptionsEnabled(),
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            ArtifactKey key = IrisTransformKeyEncoder.encode(kind, name, stages, parameters, environment);
            if (key == null) {
                IrisTransformKeyEncoder.RejectReason reason = IrisTransformKeyEncoder.lastReject();
                METRICS.recordUnkeyableRequest(reason == null ? "ENCODE_FAILURE" : reason.name());
                return original.call(name, sources, parameters);
            }
            contractProven = true;

            ArtifactCacheStore current = store();
            Optional<ShaderArtifact> cached = current.read(key);
            if (cached.isPresent() && !VERIFY) {
                Object result = materialize(cached.get());
                if (result instanceof Map<?, ?> map) {
                    return map;
                }
                METRICS.noteMiss("materialize_failed");
                current.invalidate(key);
            }

            long started = System.nanoTime();
            computed = original.call(name, sources, parameters);
            long transformNanos = Math.max(0L, System.nanoTime() - started);
            METRICS.recordFrontendTransform(transformNanos);
            Map<String, String> produced = extractStages(computed);
            if (produced == null) {
                METRICS.noteMiss("unreadable_transform_result");
                return computed;
            }
            if (VERIFY && cached.isPresent()) {
                if (transformedStagesMatch(cached.get().stages(), produced)) {
                    METRICS.recordVerifyMatch();
                } else {
                    METRICS.recordVerifyMismatch();
                    METRICS.noteMiss("verify_mismatch");
                    current.invalidate(key);
                    disable("verify_mismatch");
                }
                return computed;
            }
            if (cached.isEmpty()) {
                METRICS.noteMiss("store_miss");
                current.write(key, new ShaderArtifact(produced, transformNanos));
            }
            return computed;
        } catch (Throwable throwable) {
            METRICS.noteMiss("runtime_failure");
            disable("runtime_failure:" + throwable.getClass().getSimpleName());
            if (computed != null) {
                return computed;
            }
            return original.call(name, sources, parameters);
        }
    }

    public static boolean contractProven() {
        return contractProven && !contractRejected && !failedOpen;
    }

    public static boolean contractRejected() {
        return contractRejected;
    }

    public static String activationState() {
        if (failedOpen) {
            return "failed_open";
        }
        if (contractRejected) {
            return "contract_rejected";
        }
        if (contractProven) {
            return "active";
        }
        return "awaiting_successful_key";
    }

    public static void beginReload() {
        RELOAD_STARTED.set(System.nanoTime());
    }

    public static void endReload() {
        Long started = RELOAD_STARTED.get();
        RELOAD_STARTED.remove();
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

    public static void recordSampledReload() {
        METRICS.recordSampleReload();
    }

    /**
     * Verify mode compares the cached stage map with the map that would be returned downstream.
     * A corrupted cached source must not compare equal.
     */
    static boolean transformedStagesMatch(
            final Map<String, String> cached, final Map<String, String> produced) {
        return cached.equals(produced);
    }

    public static boolean failedOpen() {
        return failedOpen;
    }

    public static String failureReason() {
        return failureReason;
    }

    private static @org.jspecify.annotations.Nullable List<IrisTransformKeyEncoder.StageSource> stageSources(
            final Map<?, ?> sources) {
        if (sources == null) {
            return null;
        }
        List<IrisTransformKeyEncoder.StageSource> stages = new ArrayList<>(sources.size());
        for (Map.Entry<?, ?> entry : sources.entrySet()) {
            if (!(entry.getKey() instanceof Enum<?> stage)
                    || (entry.getValue() != null && !(entry.getValue() instanceof String))) {
                return null;
            }
            stages.add(new IrisTransformKeyEncoder.StageSource(stage.name(), (String)entry.getValue()));
        }
        stages.sort(java.util.Comparator.comparing(IrisTransformKeyEncoder.StageSource::stage));
        return stages;
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
            return materializeStages(artifact, enumClass);
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static @org.jspecify.annotations.Nullable Map<?, ?> materializeStages(
            final ShaderArtifact artifact, final Class<? extends Enum> enumClass) {
        try {
            EnumMap transformed = new EnumMap(enumClass);
            for (Map.Entry<String, String> stage : artifact.stages().entrySet()) {
                transformed.put(Enum.valueOf(enumClass, stage.getKey()), stage.getValue());
            }
            return transformed;
        } catch (IllegalArgumentException exception) {
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
            if (!(entry.getKey() instanceof Enum<?> stage)
                    || (entry.getValue() != null && !(entry.getValue() instanceof String))) {
                return null;
            }
            String source = (String)entry.getValue();
            if (stages.containsKey(stage.name())) {
                return null;
            }
            stages.put(stage.name(), source);
        }
        return Collections.unmodifiableMap(stages);
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
    }

    private static long positiveLongProperty(final String key, final long defaultValue) {
        Long value = Long.getLong(key);
        return value != null && value > 0L ? value : defaultValue;
    }

    private static int positiveIntegerProperty(final String key, final int defaultValue) {
        int value = Integer.getInteger(key, defaultValue);
        return value > 0 ? value : defaultValue;
    }

}
