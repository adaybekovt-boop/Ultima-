package dev.ultima.fsr;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-checked Iris public surface for a <em>finished-frame</em> post-process hook
 * and for external control of Iris internal render-target resolution.
 *
 * <p>This is a narrower question than geometry submit. The official Iris API
 * on the {@code 26.2} branch ({@code net.irisshaders.iris.api.v0.IrisApi})
 * exposes shader-pack state, config enable/disable, text sinks, sun-path
 * rotation, and {@code assignPipeline} (map a Mojang {@code RenderPipeline}
 * onto an Iris program). None of those:
 * <ul>
 *   <li>hand out the color buffer after the shader {@code final} program;</li>
 *   <li>register a callback between that program and HUD/GUI;</li>
 *   <li>set Iris gbuffer / composite render-target size from another mod.</li>
 * </ul>
 *
 * <p>Iris internals ({@code FinalPassRenderer}) do write the last pass into
 * {@code GameRenderer.mainRenderTarget()} as an implementation detail. That
 * is not a public hook: Iris issue
 * <a href="https://github.com/IrisShaders/Iris/issues/2947">#2947</a> is still
 * open asking for a stable capture point, and {@code WorldRenderEvents.LAST}
 * is documented there as seeing a cleared or reused main framebuffer.
 *
 * <p>Internal resolution is pack-authored ({@code scale.<program>},
 * {@code size.buffer.*}) or a rejected/external feature (Iris issues
 * <a href="https://github.com/IrisShaders/Iris/issues/757">#757</a>,
 * <a href="https://github.com/IrisShaders/Iris/issues/2052">#2052</a>,
 * <a href="https://github.com/IrisShaders/Iris/issues/3150">#3150</a>).
 * There is no IrisApi setter Ultima can call.
 *
 * <p>Unknown future IrisApi methods are <em>not</em> treated as a hook.
 * An allowlist must be filled and an integration written before FSR may run
 * on top of Iris. Until then this class reports both capabilities as missing.
 */
public final class FsrIrisCapabilities {
    public static final String IRIS_MOD_ID = "iris";
    public static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    public static final String IRIS_API_CONFIG_CLASS = "net.irisshaders.iris.api.v0.IrisApiConfig";

    /**
     * Instance methods on IrisApi as published on the Iris {@code 26.2} branch.
     * {@code getInstance} is static and is listed separately for the probe log.
     */
    public static final List<String> DOCUMENTED_IRIS_API_METHODS = List.of(
            "getInstance",
            "getMinorApiRevision",
            "isShaderPackInUse",
            "isRenderingShadowPass",
            "openMainIrisScreenObj",
            "getMainScreenLanguageKey",
            "getConfig",
            "createTextVertexSink",
            "getSunPathRotation",
            "assignPipeline");

    /** Official IrisApiConfig methods on the Iris {@code 26.2} branch. */
    public static final List<String> DOCUMENTED_IRIS_API_CONFIG_METHODS = List.of(
            "areShadersEnabled",
            "setShadersEnabledAndApply");

    /**
     * Official method names that would be a safe post-final / pre-GUI insertion
     * point. Empty: none exist on Iris 26.2, and none will be guessed.
     */
    public static final Set<String> KNOWN_POST_FINAL_HOOK_METHODS = Set.of();

    /**
     * Official method names that would let Ultima set Iris world/gbuffer
     * resolution. Empty: none exist on Iris 26.2.
     */
    public static final Set<String> KNOWN_INTERNAL_RESOLUTION_METHODS = Set.of();

    private FsrIrisCapabilities() {
    }

    public static boolean isIrisModLoaded() {
        Boolean override = FsrCompatibility.testIrisLoaded();
        if (override != null) {
            return override;
        }
        return FsrCompatibility.isModLoaded(IRIS_MOD_ID);
    }

    /**
     * Official, allowlisted hook after Iris {@code final} and before GUI.
     * Always {@code false} until Iris ships one and Ultima implements it.
     */
    public static boolean hasOfficialPostProcessHook() {
        return !KNOWN_POST_FINAL_HOOK_METHODS.isEmpty() && probe().hasAllowlistedPostFinalHook();
    }

    /**
     * Whether Ultima can make Iris render the world at a reduced internal
     * resolution. Always {@code false} until Iris ships a public setter.
     */
    public static boolean canControlInternalResolution() {
        return !KNOWN_INTERNAL_RESOLUTION_METHODS.isEmpty() && probe().hasAllowlistedResolutionControl();
    }

    public static IrisApiProbe probe() {
        if (!isIrisModLoaded()) {
            return IrisApiProbe.IRIS_ABSENT;
        }
        Set<String> apiMethods = inspectMethods(IRIS_API_CLASS);
        Set<String> configMethods = inspectMethods(IRIS_API_CONFIG_CLASS);
        if (apiMethods.isEmpty() && configMethods.isEmpty()) {
            return IrisApiProbe.IRIS_LOADED_API_MISSING;
        }
        boolean hook = containsAny(apiMethods, KNOWN_POST_FINAL_HOOK_METHODS)
                || containsAny(configMethods, KNOWN_POST_FINAL_HOOK_METHODS);
        boolean scale = containsAny(apiMethods, KNOWN_INTERNAL_RESOLUTION_METHODS)
                || containsAny(configMethods, KNOWN_INTERNAL_RESOLUTION_METHODS);
        return new IrisApiProbe(true, true, apiMethods, configMethods, hook, scale);
    }

    public static String summary() {
        IrisApiProbe found = probe();
        return "irisLoaded=" + found.irisModLoaded()
                + " apiPresent=" + found.apiClassPresent()
                + " officialPostFinalHook=" + hasOfficialPostProcessHook()
                + " irisInternalResolutionControllable=" + canControlInternalResolution()
                + " irisApiMethods=" + found.apiMethods()
                + " irisApiConfigMethods=" + found.configMethods();
    }

    private static Set<String> inspectMethods(final String className) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Class<?> type = Class.forName(className, false, FsrIrisCapabilities.class.getClassLoader());
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                names.add(method.getName());
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            return Set.of();
        }
        return Set.copyOf(names);
    }

    private static boolean containsAny(final Set<String> present, final Set<String> wanted) {
        if (wanted.isEmpty() || present.isEmpty()) {
            return false;
        }
        for (String name : wanted) {
            if (present.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public record IrisApiProbe(
            boolean irisModLoaded,
            boolean apiClassPresent,
            Set<String> apiMethods,
            Set<String> configMethods,
            boolean hasAllowlistedPostFinalHook,
            boolean hasAllowlistedResolutionControl) {
        public static final IrisApiProbe IRIS_ABSENT =
                new IrisApiProbe(false, false, Set.of(), Set.of(), false, false);
        public static final IrisApiProbe IRIS_LOADED_API_MISSING =
                new IrisApiProbe(true, false, Set.of(), Set.of(), false, false);

        public IrisApiProbe {
            apiMethods = Set.copyOf(apiMethods);
            configMethods = Set.copyOf(configMethods);
        }
    }
}
