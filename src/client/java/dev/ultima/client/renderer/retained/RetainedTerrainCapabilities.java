package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether the retained opaque path may run. Fail closed to vanilla.
 *
 * <p>Table indexing uses {@code gl_BaseInstanceARB} (the identifier Minecraft
 * 26.2's shaderc provides on OpenGL and Vulkan). That requires
 * {@code shaderDrawParameters} plus either indirect {@code firstInstance}
 * or {@code nonZeroFirstInstance}.
 */
public final class RetainedTerrainCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-retained-terrain");
    private static Boolean cached;
    private static String cachedMode = "none";

    private RetainedTerrainCapabilities() {
    }

    public static void invalidate() {
        cached = null;
        cachedMode = "none";
    }

    public static boolean available() {
        Boolean local = cached;
        if (local != null) {
            return local;
        }
        boolean result = detect();
        cached = result;
        return result;
    }

    private static boolean detect() {
        if (FabricLoader.getInstance().isModLoaded("sodium")
                || FabricLoader.getInstance().isModLoaded("iris")
                || FabricLoader.getInstance().isModLoaded("canvas")) {
            LOGGER.info("Retained terrain disabled: a replacement renderer/shader mod is loaded.");
            cachedMode = "incompatible_mod";
            return false;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            cachedMode = "no_device";
            return false;
        }

        DeviceFeatures features = device.getDeviceInfo().features();
        if (!features.shaderDrawParameters()) {
            LOGGER.info("Retained terrain disabled: device lacks shaderDrawParameters.");
            cachedMode = "no_shader_draw_parameters";
            return false;
        }

        cachedMode = modeFor(features);
        if ("unsupported".equals(cachedMode)) {
            LOGGER.info("Retained terrain disabled: no indirect firstInstance or non-zero base instance.");
            return false;
        }
        LOGGER.info("Retained terrain submit mode: {}", cachedMode);
        return true;
    }

    public static String describeSubmitMode() {
        if (cached == null) {
            available();
        }
        return cachedMode;
    }

    private static String modeFor(final DeviceFeatures features) {
        if (features.drawIndirect() && features.multiDrawIndirect()) {
            return "indirect";
        }
        if (features.drawIndirect()) {
            return "indirect_single";
        }
        if (features.nonZeroFirstInstance()) {
            return "base_instance_loop";
        }
        return "unsupported";
    }
}
