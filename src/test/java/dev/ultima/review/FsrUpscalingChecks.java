package dev.ultima.review;

import dev.ultima.config.LoadedModCache;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaMixinPlugin;
import dev.ultima.config.UltimaModules;
import dev.ultima.client.fsr.FsrUpscaling;
import dev.ultima.fsr.FsrCompatibility;
import dev.ultima.fsr.FsrEasuConstants;
import dev.ultima.fsr.FsrIrisCapabilities;
import dev.ultima.fsr.FsrLatencyContract;
import dev.ultima.fsr.FsrRuntimeGate;
import dev.ultima.fsr.FsrPipelineGate;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrResolution;
import dev.ultima.fsr.FsrResourcePlan;
import dev.ultima.fsr.FsrScreenshotPolicy;
import dev.ultima.fsr.FsrSettings;
import dev.ultima.fsr.FsrSize;
import dev.ultima.fsr.FsrSkySafetyModel;
import dev.ultima.fsr.FsrTargetModel;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * GPU-free coverage for FSR1 planning, AMD constant setup, resize lifecycle,
 * C1 SkyRenderer park/rebind, H1 screenshot deferral, default-off gating,
 * Sodium-only / Iris capability policy, and the FSR1 latency contract.
 */
final class FsrUpscalingChecks {
    private FsrUpscalingChecks() {
    }

    static void run() {
        testPresetScaleFactors();
        testInternalResolutionRounding();
        testOddAndTinyNativeSizes();
        testEasuConstantsAgainstOfficialFormula();
        testEasuOfficialWorkedExample();
        testRcasConstants();
        testResizeLifecycleNotStale();
        testDisabledPlanAllocatesNothing();
        testFailOpenParksWorldTargetAndRebindsSky();
        testOneByOneParkDoesNotDestroyWorldTarget();
        testScreenshotDeferredUntilAfterRcas();
        testCompatibilityPolicy();
        testIrisCapabilityGates();
        testLatencyContract();
        testRuntimeGate();
        testPipelineGate();
        testModuleRegistryAndDefaults();
        testSettingsParsing();
        testResizeMixinPriority();
        testIrisModPresenceIsCachedAndShared();
        testIrisProtectionIsMixinPlugin();
        System.out.println("FSR upscaling checks passed.");
    }

    private static void testPresetScaleFactors() {
        assertEqualsDouble(1.0 / 1.3, FsrQualityPreset.ULTRA_QUALITY.scaleFactor(), 1e-12, "ultra quality scale");
        assertEqualsDouble(1.0 / 1.5, FsrQualityPreset.QUALITY.scaleFactor(), 1e-12, "quality scale");
        assertEqualsDouble(1.0 / 1.7, FsrQualityPreset.BALANCED.scaleFactor(), 1e-12, "balanced scale");
        assertEqualsDouble(0.5, FsrQualityPreset.PERFORMANCE.scaleFactor(), 1e-12, "performance scale");
        assertEqualsDouble(1.0 / 3.0, FsrQualityPreset.ULTRA_PERFORMANCE.scaleFactor(), 1e-12, "ultra performance scale");
        assertTrue(FsrQualityPreset.fromKey("ultra-quality") == FsrQualityPreset.ULTRA_QUALITY, "preset key parse");
        assertTrue(FsrQualityPreset.fromKey("not-a-preset") == FsrQualityPreset.QUALITY, "unknown preset is Quality");
        assertTrue("Ultra Quality".equals(FsrQualityPreset.ULTRA_QUALITY.displayName()), "Ultra Quality label");
        assertTrue("Quality".equals(FsrQualityPreset.QUALITY.displayName()), "Quality label");
        assertTrue("Balanced".equals(FsrQualityPreset.BALANCED.displayName()), "Balanced label");
        assertTrue("Performance".equals(FsrQualityPreset.PERFORMANCE.displayName()), "Performance label");
        assertTrue("Ultra Performance".equals(FsrQualityPreset.ULTRA_PERFORMANCE.displayName()), "Ultra Performance label");
    }

    private static void testInternalResolutionRounding() {
        FsrSize quality = FsrResolution.internal(FsrQualityPreset.QUALITY, 1920, 1080);
        assertEquals(1280, quality.width(), "1920 Quality width");
        assertEquals(720, quality.height(), "1080 Quality height");

        FsrSize ultra = FsrResolution.internal(FsrQualityPreset.ULTRA_QUALITY, 1920, 1080);
        assertEquals(Math.round(1920 * (1.0 / 1.3)), ultra.width(), "1920 Ultra Quality width");
        assertEquals(Math.round(1080 * (1.0 / 1.3)), ultra.height(), "1080 Ultra Quality height");

        FsrSize balanced = FsrResolution.internal(FsrQualityPreset.BALANCED, 1920, 1080);
        assertEquals(Math.round(1920 / 1.7), balanced.width(), "1920 Balanced width");
        assertEquals(Math.round(1080 / 1.7), balanced.height(), "1080 Balanced height");

        FsrSize performance = FsrResolution.internal(FsrQualityPreset.PERFORMANCE, 1920, 1080);
        assertEquals(960, performance.width(), "1920 Performance width");
        assertEquals(540, performance.height(), "1080 Performance height");

        FsrSize ultraPerf = FsrResolution.internal(FsrQualityPreset.ULTRA_PERFORMANCE, 1920, 1080);
        assertEquals(640, ultraPerf.width(), "1920 Ultra Performance width");
        assertEquals(360, ultraPerf.height(), "1080 Ultra Performance height");
    }

    private static void testOddAndTinyNativeSizes() {
        FsrSize odd = FsrResolution.internal(FsrQualityPreset.QUALITY, 1367, 769);
        assertEquals(Math.round(1367 / 1.5), odd.width(), "odd Quality width");
        assertEquals(Math.round(769 / 1.5), odd.height(), "odd Quality height");
        assertTrue(odd.width() >= 1 && odd.width() <= 1367, "odd width clamped");
        assertTrue(odd.height() >= 1 && odd.height() <= 769, "odd height clamped");

        assertEquals(1, FsrResolution.scaleAxis(1, 0.5), "1-pixel axis stays at least 1");
        assertEquals(1, FsrResolution.scaleAxis(2, 1.0 / 3.0), "2px ultra-perf rounds to 1");
        assertEquals(5, FsrResolution.scaleAxis(5, 2.0), "scale > 1 must not super-sample");
        assertEquals(7, FsrResolution.scaleAxis(7, 0.0), "non-positive scale stays native");
        assertEquals(7, FsrResolution.scaleAxis(7, Double.NaN), "NaN scale stays native");

        FsrSize one = FsrResolution.internal(FsrQualityPreset.PERFORMANCE, 1, 1);
        assertTrue(FsrResolution.isNativeResolution(one, 1, 1), "1x1 Performance is native after clamp");
        FsrResourcePlan tiny = FsrResourcePlan.decide(true, FsrQualityPreset.PERFORMANCE, 1, 1);
        assertFalse(tiny.runUpscale(), "native-equal plan must not upscale");
        assertEquals(0, tiny.extraColorTargets(), "native-equal plan allocates no extra targets");
    }

    private static void testEasuConstantsAgainstOfficialFormula() {
        FsrEasuConstants.EasuCon con = FsrEasuConstants.easuSameViewportAndInput(1280, 720, 1920, 1080);
        // Official FsrEasuCon multiplies by ARcpF1(x) == 1.0f/x, not a/b.
        float rcpOutX = FsrEasuConstants.rcp(1920.0F);
        float rcpOutY = FsrEasuConstants.rcp(1080.0F);
        float rcpInX = FsrEasuConstants.rcp(1280.0F);
        float rcpInY = FsrEasuConstants.rcp(720.0F);
        assertEqualsFloat(1280.0F * rcpOutX, con.con0()[0], "con0.x viewportX/outputX");
        assertEqualsFloat(720.0F * rcpOutY, con.con0()[1], "con0.y viewportY/outputY");
        assertEqualsFloat(0.5F * 1280.0F * rcpOutX - 0.5F, con.con0()[2], "con0.z");
        assertEqualsFloat(0.5F * 720.0F * rcpOutY - 0.5F, con.con0()[3], "con0.w");
        assertEqualsFloat(rcpInX, con.con1()[0], "con1.x 1/inputX");
        assertEqualsFloat(rcpInY, con.con1()[1], "con1.y 1/inputY");
        assertEqualsFloat(1.0F * rcpInX, con.con1()[2], "con1.z");
        assertEqualsFloat(-1.0F * rcpInY, con.con1()[3], "con1.w");
        assertEqualsFloat(-1.0F * rcpInX, con.con2()[0], "con2.x");
        assertEqualsFloat(2.0F * rcpInY, con.con2()[1], "con2.y");
        assertEqualsFloat(1.0F * rcpInX, con.con2()[2], "con2.z");
        assertEqualsFloat(2.0F * rcpInY, con.con2()[3], "con2.w");
        assertEqualsFloat(0.0F, con.con3()[0], "con3.x");
        assertEqualsFloat(4.0F * rcpInY, con.con3()[1], "con3.y");
        assertEqualsFloat(0.0F, con.con3()[2], "con3.z");
        assertEqualsFloat(0.0F, con.con3()[3], "con3.w");
        assertEquals(Float.floatToIntBits(con.con0()[0]), con.conBits()[0][0], "AU1_AF1 con0.x");
        assertEquals(Float.floatToIntBits(con.con1()[3]), con.conBits()[1][3], "AU1_AF1 con1.w");
    }

    private static void testEasuOfficialWorkedExample() {
        // Comment in ffx_fsr1.h: viewport 1920x1080, input 3840x2160, output 2560x1440.
        FsrEasuConstants.EasuCon con = FsrEasuConstants.easu(1920.0F, 1080.0F, 3840.0F, 2160.0F, 2560.0F, 1440.0F);
        float rcpOutX = FsrEasuConstants.rcp(2560.0F);
        float rcpOutY = FsrEasuConstants.rcp(1440.0F);
        float rcpInX = FsrEasuConstants.rcp(3840.0F);
        float rcpInY = FsrEasuConstants.rcp(2160.0F);
        assertEqualsFloat(1920.0F * rcpOutX, con.con0()[0], "official example con0.x");
        assertEqualsFloat(1080.0F * rcpOutY, con.con0()[1], "official example con0.y");
        assertEqualsFloat(0.5F * 1920.0F * rcpOutX - 0.5F, con.con0()[2], "official example con0.z");
        assertEqualsFloat(0.5F * 1080.0F * rcpOutY - 0.5F, con.con0()[3], "official example con0.w");
        assertEqualsFloat(rcpInX, con.con1()[0], "official example con1.x");
        assertEqualsFloat(rcpInY, con.con1()[1], "official example con1.y");
        assertEqualsFloat(-1.0F * rcpInX, con.con2()[0], "official example con2.x");
        assertEqualsFloat(4.0F * rcpInY, con.con3()[1], "official example con3.y");
    }

    private static void testRcasConstants() {
        FsrEasuConstants.RcasCon zero = FsrEasuConstants.rcas(0.0F);
        assertEqualsFloat(1.0F, zero.sharpnessLinear(), "0 stops is exp2(0)=1");
        FsrEasuConstants.RcasCon defaultStops = FsrEasuConstants.rcas(0.2F);
        float expected = (float)Math.exp(Math.log(2.0) * -0.2);
        assertEqualsFloat(expected, defaultStops.sharpnessLinear(), "0.2 stops matches exp2(-0.2)");
        assertEquals(Float.floatToIntBits(expected), defaultStops.sharpnessBits(), "RCAS AU1_AF1");
        assertEqualsFloat(0.2F, FsrSettings.clampSharpness(0.2F), "default sharpness in range");
        assertEqualsFloat(0.0F, FsrSettings.clampSharpness(-4.0F), "sharpness floors at 0");
        assertEqualsFloat(2.0F, FsrSettings.clampSharpness(9.0F), "sharpness caps at 2");
        assertEqualsFloat(FsrSettings.DEFAULT_SHARPNESS_STOPS, FsrSettings.clampSharpness(Float.NaN), "NaN sharpness");
    }

    private static void testResizeLifecycleNotStale() {
        FsrTargetModel model = new FsrTargetModel();
        FsrResourcePlan q1080 = FsrResourcePlan.decide(true, FsrQualityPreset.QUALITY, 1920, 1080);
        model.onNativeResize(q1080);
        assertTrue(model.hasLiveTargets(), "enabled plan allocates");
        assertEquals(2, model.liveTargetCount(), "world + EASU");
        assertTrue(q1080.internal().equalsSize(model.worldSize().width(), model.worldSize().height()), "world is internal");
        assertTrue(q1080.output().equalsSize(model.easuSize().width(), model.easuSize().height()), "EASU is native");
        int allocations = model.allocations();
        model.onNativeResize(q1080);
        assertEquals(allocations, model.allocations(), "same native size must not reallocate");
        assertFalse(model.isStale(q1080), "same plan is not stale");

        FsrResourcePlan q720 = FsrResourcePlan.decide(true, FsrQualityPreset.QUALITY, 1280, 720);
        assertTrue(model.isStale(q720), "new native size is stale before apply");
        model.onNativeResize(q720);
        assertTrue(q720.internal().equalsSize(model.worldSize().width(), model.worldSize().height()), "world resized");
        assertTrue(q720.output().equalsSize(model.easuSize().width(), model.easuSize().height()), "EASU resized");
        assertTrue(model.resizes() >= 1, "native change is a resize, not a leak of the old size");
        assertFalse(model.isStale(q720), "after apply the new size is current");
    }

    private static void testDisabledPlanAllocatesNothing() {
        FsrResourcePlan disabled = FsrResourcePlan.decide(false, FsrQualityPreset.PERFORMANCE, 1920, 1080);
        assertFalse(disabled.redirectMainTarget(), "disabled does not redirect");
        assertFalse(disabled.allocateWorldTarget(), "disabled does not allocate world");
        assertFalse(disabled.allocateEasuTarget(), "disabled does not allocate EASU");
        assertFalse(disabled.runUpscale(), "disabled does not upscale");
        assertEquals(0, disabled.extraColorTargets(), "disabled extra targets");
        assertTrue(disabled.internal().equalsSize(1920, 1080), "disabled internal equals native");

        FsrTargetModel neverOn = new FsrTargetModel();
        neverOn.apply(disabled);
        assertFalse(neverOn.hasLiveTargets(), "never-enabled plan allocates nothing");
        assertEquals(0, neverOn.allocations(), "zero allocations when FSR was never on");
        assertFalse(neverOn.consumeSkyResetRequired(), "no sky reset when nothing was captured");

        FsrTargetModel model = new FsrTargetModel();
        model.apply(FsrResourcePlan.decide(true, FsrQualityPreset.QUALITY, 1920, 1080));
        assertTrue(model.hasLiveTargets(), "precondition: targets exist");
        model.consumeSkyResetRequired();
        model.apply(disabled);
        assertTrue(model.hasLiveTargets(), "C1: mid-session inactive plan parks the world target");
        assertTrue(model.isParked(), "world target is parked, not destroyed");
        assertTrue(model.worldSize() != null, "parked world size remains");
        assertTrue(model.easuSize() == null, "EASU ping buffer may be released");
        assertTrue(model.consumeSkyResetRequired(), "park requests SkyRenderer rebind");
        assertTrue(model.outlineSize().equalsSize(1920, 1080), "outline restored to native when parked");
        model.destroyForLifecycle();
        assertFalse(model.hasLiveTargets(), "lifecycle close is the only world-target destroy");
    }

    private static void testFailOpenParksWorldTargetAndRebindsSky() {
        FsrSkySafetyModel.SkyRendererStub sky = new FsrSkySafetyModel.SkyRendererStub();
        FsrSkySafetyModel oldBug = new FsrSkySafetyModel();
        oldBug.allocateWorld();
        sky.bind(oldBug.world());
        oldBug.destroyWorldWithoutSkyReset();
        boolean crashed = false;
        try {
            sky.drawSkyPass();
        } catch (IllegalStateException e) {
            crashed = true;
            assertTrue(e.getMessage().contains("closed FSR world target"), "old bug is a closed-target use");
        }
        assertTrue(crashed, "pre-fix destroy-without-reset must throw on the next sky pass");

        FsrSkySafetyModel.SkyRendererStub safeSky = new FsrSkySafetyModel.SkyRendererStub();
        FsrSkySafetyModel fixed = new FsrSkySafetyModel();
        fixed.allocateWorld();
        safeSky.bind(fixed.world());
        safeSky.drawSkyPass();
        fixed.failOpenPark();
        assertFalse(fixed.worldDestroyed(), "C1 (a): fail-open does not destroy the world target");
        assertTrue(fixed.isParked(), "fail-open parks the world target");
        fixed.nextSkyPass(safeSky);
        assertTrue(safeSky.bound() == fixed.vanillaMain(), "C1 (b): next sky pass rebinds to vanilla main");
        safeSky.drawSkyPass();
    }

    private static void testOneByOneParkDoesNotDestroyWorldTarget() {
        FsrTargetModel model = new FsrTargetModel();
        model.apply(FsrResourcePlan.decide(true, FsrQualityPreset.QUALITY, 1920, 1080));
        model.consumeSkyResetRequired();
        FsrResourcePlan tiny = FsrResourcePlan.decide(true, FsrQualityPreset.PERFORMANCE, 1, 1);
        assertFalse(tiny.runUpscale(), "1x1 Performance is native-equal");
        assertTrue(model.isStale(tiny) || model.hasLiveTargets(), "1x1 after 1080 still has a live world target");
        model.apply(tiny);
        assertTrue(model.isParked(), "1x1 parks instead of close()");
        assertTrue(model.worldSize() != null, "1x1 must not destroy the captured world target");
        assertTrue(model.consumeSkyResetRequired(), "1x1 requests sky rebind to vanilla");

        FsrSkySafetyModel safety = new FsrSkySafetyModel();
        FsrSkySafetyModel.SkyRendererStub sky = new FsrSkySafetyModel.SkyRendererStub();
        safety.allocateWorld();
        sky.bind(safety.world());
        safety.parkForInactivePlan();
        safety.nextSkyPass(sky);
        sky.drawSkyPass();
        assertFalse(safety.worldDestroyed(), "1x1 park leaves the world handle usable");
    }

    private static void testScreenshotDeferredUntilAfterRcas() {
        assertTrue(
                FsrScreenshotPolicy.onVanillaScreenshotCall(true) == FsrScreenshotPolicy.Action.DEFER_UNTIL_AFTER_RCAS,
                "H1: world-pass screenshot is deferred until after RCAS");
        assertTrue(
                FsrScreenshotPolicy.onVanillaScreenshotCall(false) == FsrScreenshotPolicy.Action.TAKE_NOW,
                "menu / non-FSR frames take the vanilla screenshot immediately");
        assertTrue(
                FsrScreenshotPolicy.captureAfterRcas(true, true),
                "deferred screenshot fires after a successful upscale");
        assertFalse(
                FsrScreenshotPolicy.captureAfterRcas(true, false),
                "fail-open after renderLevel skips the empty vanilla-main capture");
        assertFalse(
                FsrScreenshotPolicy.captureAfterRcas(false, true),
                "no capture when nothing was deferred");
    }

    private static void testCompatibilityPolicy() {
        assertTrue(
                FsrCompatibility.evaluate(true, false, false)
                        == FsrCompatibility.DisableReason.NO_SAFE_POST_IRIS_INTEGRATION_POINT,
                "Iris disables FSR because there is no official post-final hook");
        assertTrue(
                FsrCompatibility.evaluate(false, true, false) == FsrCompatibility.DisableReason.CANVAS_OWNS_RENDERER,
                "Canvas disables FSR");
        assertTrue(
                FsrCompatibility.evaluate(true, true, true) == FsrCompatibility.DisableReason.CANVAS_OWNS_RENDERER,
                "Canvas owns the whole renderer when both Canvas and Iris are present");
        assertTrue(
                FsrCompatibility.evaluate(false, false, true) == FsrCompatibility.DisableReason.NONE,
                "Sodium-only does not unconditionally disable FSR");
        assertTrue(
                FsrCompatibility.evaluate(false, false, false) == FsrCompatibility.DisableReason.NONE,
                "vanilla-only is allowed");
        assertTrue(
                FsrCompatibility.allowsWithSodiumOnly(true, false, false),
                "Sodium-only is allowed");
        assertFalse(
                FsrCompatibility.allowsWithSodiumOnly(true, true, false),
                "Sodium+Iris is not allowed without a post-Iris hook");
        assertTrue(
                FsrCompatibility.evaluate(true, false, false).configReason()
                        .equals(FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK),
                "Iris reason is not incompatible_mod");
        assertFalse(
                "incompatible_mod".equals(FsrCompatibility.evaluate(true, false, false).configReason()),
                "Iris must not reuse the old incompatible_mod reason");

        UltimaModules.Module module = UltimaModules.byKey("fsr_upscaling");
        assertTrue(module != null, "fsr_upscaling is registered");
        assertFalse(module.incompatibleMods().contains("sodium"),
                "Sodium is not an unconditional FSR incompatible mod");
        assertFalse(module.incompatibleMods().contains("iris"),
                "Iris is gated by capability reasons, not incompatibleMods");
        assertTrue(module.incompatibleMods().contains("canvas"), "Canvas is a declared renderer owner");
        assertTrue(module.incompatibleMods().equals(FsrCompatibility.unconditionalIncompatibleModIds()),
                "registry incompatibleMods is FsrCompatibility.unconditionalIncompatibleModIds()");
        assertTrue(module.incompatibleMods().equals(FsrCompatibility.disablingModIds()),
                "disablingModIds stays the unconditional Canvas list");
        assertFalse(module.incompatibleMods().contains("lithium"), "FSR is not a simulation module");
        assertTrue(module.dependencies().isEmpty(), "FSR is isolated from retained/mesher modules");
        assertFalse(module.enabledByDefault(), "FSR is default off");
        assertTrue(module.clientOnly(), "FSR is client-only");

        FsrCompatibility.runWithLoadedModsForTest(true, false, true, () -> {
            try {
                Constructor<UltimaConfig> constructor = UltimaConfig.class.getDeclaredConstructor(Map.class);
                constructor.setAccessible(true);
                Map<String, Boolean> requested = new LinkedHashMap<>();
                for (UltimaModules.Module registered : UltimaModules.all()) {
                    requested.put(registered.key(), registered.enabledByDefault());
                }
                requested.put("fsr_upscaling", true);
                UltimaConfig config = constructor.newInstance(requested);
                UltimaConfig.ResolvedModule resolved = config.resolve("fsr_upscaling");
                assertFalse(resolved.enabled(), "requested FSR stays disabled under Sodium+Iris");
                assertTrue(FsrCompatibility.blocks("fsr_upscaling"),
                        "capability gate reports Iris as blocking");
                assertTrue(
                        FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK.equals(resolved.reason())
                                || "not_client_environment".equals(resolved.reason()),
                        "Sodium+Iris resolve reason is the hook finding on the client, "
                                + "or client-only on a dedicated server, not " + resolved.reason());
                assertFalse(
                        "incompatible_mod".equals(resolved.reason()),
                        "Iris must not reuse incompatible_mod");
                if (FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK.equals(resolved.reason())) {
                    assertTrue(
                            resolved.detail().contains("no official hook"),
                            "Iris disable detail names the missing hook");
                    assertTrue(
                            resolved.detail().contains("internal render-target resolution"),
                            "Iris disable detail also records the resolution limitation");
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not resolve FSR under a fake Iris load", e);
            }
        });
    }

    private static void testIrisCapabilityGates() {
        assertTrue(FsrIrisCapabilities.KNOWN_POST_FINAL_HOOK_METHODS.isEmpty(),
                "no official post-final hook is allowlisted");
        assertTrue(FsrIrisCapabilities.KNOWN_INTERNAL_RESOLUTION_METHODS.isEmpty(),
                "no official Iris resolution setter is allowlisted");
        assertFalse(FsrIrisCapabilities.hasOfficialPostProcessHook(),
                "Iris 26.2 public API has no finished-frame hook");
        assertFalse(FsrIrisCapabilities.canControlInternalResolution(),
                "Iris internal resolution is not controllable from Ultima");
        assertTrue(FsrIrisCapabilities.DOCUMENTED_IRIS_API_METHODS.contains("assignPipeline"),
                "re-checked API includes assignPipeline");
        assertTrue(FsrIrisCapabilities.DOCUMENTED_IRIS_API_METHODS.contains("isShaderPackInUse"),
                "re-checked API includes isShaderPackInUse");
        assertFalse(FsrIrisCapabilities.DOCUMENTED_IRIS_API_METHODS.contains("getFinalColorTexture"),
                "IrisApi does not expose a final color texture");
        assertFalse(FsrIrisCapabilities.DOCUMENTED_IRIS_API_CONFIG_METHODS.contains("setRenderScale"),
                "IrisApiConfig has no render-scale setter");
        for (String method : FsrIrisCapabilities.DOCUMENTED_IRIS_API_METHODS) {
            String lower = method.toLowerCase();
            assertFalse(lower.contains("finalcolor") || lower.contains("postprocess")
                            || lower.contains("afterfinal") || lower.contains("composited"),
                    method + " is not a finished-frame capture hook");
        }
        assertTrue(FsrIrisCapabilities.probe() == FsrIrisCapabilities.IrisApiProbe.IRIS_ABSENT
                        || !FsrIrisCapabilities.probe().hasAllowlistedPostFinalHook(),
                "absent Iris or empty allowlist means no hook");
    }

    private static void testLatencyContract() {
        assertFalse(FsrLatencyContract.usesTemporalHistory(), "FSR1 is spatial");
        assertFalse(FsrLatencyContract.holdsCurrentFrameForInterpolation(),
                "FSR1 does not hold the current frame for the next");
        assertTrue(FsrLatencyContract.extraDisplayedFramesOfDelay() == 0,
                "no extra displayed frame of delay");
        assertFalse(FsrLatencyContract.addsGpuPassWhenIrisPresent(),
                "Iris path does not run FSR, so added GPU pass time is zero");
        assertTrue(FsrLatencyContract.ADDED_COST.contains("EASU+RCAS"),
                "documented added cost is the spatial pass, not a queued frame");
    }

    private static void testRuntimeGate() {
        assertTrue(FsrRuntimeGate.allowWorldTargetHijack(true, false, false),
                "vanilla/Sodium-only world hijack is allowed");
        assertFalse(FsrRuntimeGate.allowWorldTargetHijack(true, false, true),
                "Iris present refuses the world-target hijack");
        assertFalse(FsrRuntimeGate.allowWorldTargetHijack(true, true, false),
                "failed-open FSR never hijacks");
        assertFalse(FsrRuntimeGate.allowWorldTargetHijack(false, false, false),
                "disabled module never hijacks");
    }

    private static void testIrisModPresenceIsCachedAndShared() {
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        LoadedModCache.runWithProbeForTest(
                id -> {
                    probes.incrementAndGet();
                    return "canvas".equals(id) || "iris".equals(id) || "sodium".equals(id);
                },
                () -> {
                    Map<String, Boolean> requested = new LinkedHashMap<>();
                    for (UltimaModules.Module module : UltimaModules.all()) {
                        requested.put(module.key(), module.enabledByDefault());
                    }
                    requested.put("fsr_upscaling", true);
                    UltimaConfig config = UltimaConfig.createForTests(requested);

                    UltimaConfig.ResolvedModule resolved = config.resolve("fsr_upscaling");
                    assertTrue(
                            resolved.loadedIncompatibleMods().contains("canvas"),
                            "UltimaConfig.loadedIncompatibleMods must use LoadedModCache, not raw FabricLoader");
                    assertTrue(
                            FsrCompatibility.current() == FsrCompatibility.DisableReason.CANVAS_OWNS_RENDERER,
                            "FsrCompatibility.canvasLoaded must use LoadedModCache, not raw FabricLoader");
                    assertTrue(
                            FsrIrisCapabilities.isIrisModLoaded(),
                            "FsrIrisCapabilities must use LoadedModCache, not raw FabricLoader");

                    simulateFsrFrameModProbes(config);
                    int afterWarmup = probes.get();
                    assertTrue(afterWarmup > 0, "warmup must probe the backend at least once");
                    assertEquals(
                            afterWarmup,
                            LoadedModCache.probeCallCount(),
                            "LoadedModCache probe counter must match the test probe");
                    for (int frame = 0; frame < 5; frame++) {
                        simulateFsrFrameModProbes(config);
                    }
                    assertEquals(
                            afterWarmup,
                            probes.get(),
                            "FSR per-frame paths must not call isModLoaded after cache warmup");
                });
    }

    /**
     * Production methods {@code GameRendererMixin} hits every world frame for mod presence:
     * {@code moduleEnabled} (UltimaConfig), {@code FsrCompatibility.current}/{@code blocks},
     * {@code FsrIrisCapabilities.isIrisModLoaded}, companion-target sizing.
     */
    private static void simulateFsrFrameModProbes(final UltimaConfig config) {
        config.isEnabled("fsr_upscaling");
        config.resolve("fsr_upscaling");
        FsrCompatibility.current();
        FsrCompatibility.blocks("fsr_upscaling");
        boolean irisLoaded = FsrIrisCapabilities.isIrisModLoaded();
        FsrRuntimeGate.allowWorldTargetHijack(config.isEnabled("fsr_upscaling"), false, irisLoaded);
        FsrUpscaling.get().moduleEnabled();
        FsrUpscaling.get().companionTargetSize(1920, 1080);
        FsrUpscaling.get().syncCompanionTarget(null, 1920, 1080);
    }

    private static void testIrisProtectionIsMixinPlugin() {
        UltimaMixinPlugin plugin = new UltimaMixinPlugin();
        assertFalse(
                plugin.shouldApplyMixin(
                        "net.minecraft.client.renderer.GameRenderer",
                        "dev.ultima.mixin.fsr_upscaling.GameRendererMixin"),
                "UltimaMixinPlugin must skip fsr_upscaling mixins while the module is inactive");
        LoadedModCache.runWithProbeForTest(
                "iris"::equals,
                () -> {
                    Map<String, Boolean> requested = new LinkedHashMap<>();
                    for (UltimaModules.Module module : UltimaModules.all()) {
                        requested.put(module.key(), module.enabledByDefault());
                    }
                    requested.put("fsr_upscaling", true);
                    UltimaConfig on = UltimaConfig.createForTests(requested);
                    assertTrue(FsrCompatibility.blocks("fsr_upscaling"), "Iris capability gate blocks FSR");
                    assertFalse(on.isEnabled("fsr_upscaling"), "requested FSR stays disabled under Iris");
                });
        assertTrue(
                "dev.ultima.mixin.fsr_upscaling"
                        .equals(dev.ultima.mixin.fsr_upscaling.GameRendererMixin.class.getPackageName()),
                "GameRendererMixin lives in the fsr_upscaling package the plugin gates");
    }

    private static void testPipelineGate() {
        assertTrue(
                FsrPipelineGate.decide(true, false) == FsrPipelineGate.Action.PROCEED,
                "compiled pipelines proceed");
        assertTrue(
                FsrPipelineGate.decide(false, false) == FsrPipelineGate.Action.WAIT_FOR_PIPELINES,
                "missing device/shaders wait and retry; do not fail-open");
        assertTrue(
                FsrPipelineGate.decide(false, true) == FsrPipelineGate.Action.FAIL_OPEN,
                "hard compile failure fail-opens");
        assertTrue(
                FsrPipelineGate.decide(true, true) == FsrPipelineGate.Action.PROCEED,
                "already-compiled wins over a stale failed flag");
    }

    private static void testModuleRegistryAndDefaults() {
        try {
            Constructor<UltimaConfig> constructor = UltimaConfig.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            Map<String, Boolean> defaults = new LinkedHashMap<>();
            for (UltimaModules.Module module : UltimaModules.all()) {
                defaults.put(module.key(), module.enabledByDefault());
            }
            assertFalse(defaults.get("fsr_upscaling"), "fsr_upscaling default is false");
            assertFalse(defaults.get("retained_terrain"), "retained stays opt-in");
            UltimaConfig config = constructor.newInstance(defaults);
            assertFalse(config.isEnabled("fsr_upscaling"), "default config does not enable FSR");
            assertFalse(config.isRequested("fsr_upscaling"), "default request is false");
            String reason = config.resolve("fsr_upscaling").reason();
            assertTrue(
                    "disabled_by_default".equals(reason) || "not_client_environment".equals(reason),
                    "FSR default reason, not " + reason);
            assertTrue(config.fsrSettings().preset() == FsrQualityPreset.QUALITY, "default preset is Quality");
            assertEqualsFloat(0.2F, config.fsrSettings().sharpnessStops(), "default sharpness");

            Map<String, Boolean> requested = new LinkedHashMap<>(defaults);
            requested.put("fsr_upscaling", true);
            UltimaConfig on = constructor.newInstance(requested);
            String onReason = on.resolve("fsr_upscaling").reason();
            assertTrue(
                    "enabled".equals(onReason) || "not_client_environment".equals(onReason),
                    "requested FSR is enabled on the client, not " + onReason);
            assertTrue(UltimaModules.isOptInExperiment("fsr_upscaling"), "FSR is an opt-in experiment");
            assertTrue(UltimaModules.kind(module("fsr_upscaling")) == UltimaModules.Kind.OPT_IN_EXPERIMENT, "kind");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not exercise FSR config", e);
        }
    }

    private static void testSettingsParsing() {
        Properties properties = new Properties();
        properties.setProperty(FsrSettings.PRESET_KEY, "balanced");
        properties.setProperty(FsrSettings.SHARPNESS_KEY, "0.35");
        FsrSettings settings = FsrSettings.fromProperties(properties);
        assertTrue(settings.preset() == FsrQualityPreset.BALANCED, "parsed balanced");
        assertEqualsFloat(0.35F, settings.sharpnessStops(), "parsed sharpness");
        assertTrue(FsrSettings.parseFloat("nope") == null, "invalid sharpness");
        assertEqualsFloat(0.2F, FsrSettings.fromProperties(new Properties()).sharpnessStops(), "missing sharpness");
    }

    /**
     * Mixin-wiring: {@code @Mixin} is CLASS retention, so reflection cannot read priority.
     * This reads the two GameRenderer mixin sources. Resize apply order is not proven live.
     */
    private static void testResizeMixinPriority() {
        String temporal = readSource("src/client/java/dev/ultima/mixin/temporal/GameRendererMixin.java");
        String fsr = readSource("src/client/java/dev/ultima/mixin/fsr_upscaling/GameRendererMixin.java");
        assertTrue(temporal.contains("priority = 900"), "temporal GameRenderer mixin is 900");
        assertTrue(
                fsr.contains("priority = 1100"),
                "FSR GameRenderer mixin is 1100 so resize sees native size after temporal");
        assertTrue(temporal.contains("method = \"resize\""), "temporal still injects GameRenderer.resize");
        assertTrue(fsr.contains("method = \"resize\""), "FSR still injects GameRenderer.resize");
    }

    private static String readSource(final String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static UltimaModules.Module module(final String key) {
        return UltimaModules.byKey(key);
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEqualsFloat(final float expected, final float actual, final String message) {
        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)
                && Math.abs(expected - actual) > 0.0F) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEqualsDouble(
            final double expected, final double actual, final double epsilon, final String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
