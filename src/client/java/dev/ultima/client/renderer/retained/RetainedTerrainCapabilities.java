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
 * <p>Table indexing requires {@code shaderDrawParameters}. Without multi-draw,
 * indirect, or non-zero base instance, every draw would fetch slot 0 and the
 * image would be wrong, so the path stays off.
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
            LOGGER.info("Retained terrain disabled: no multi-draw, indirect, or base-instance indexing.");
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

    public static int maxDrawsPerBatch() {
        GpuDevice device = RenderSystem.tryGetDevice();
        int cap = RetainedTerrainPipelines.BATCH_SIZE;
        if (device == null) {
            return cap;
        }
        DeviceFeatures features = device.getDeviceInfo().features();
        if (!features.multiDrawIndirect() && !features.drawIndirect() && !features.multiDrawDirectSeparate()
                && features.multiDrawDirectInterleaved()) {
            int limit = device.getDeviceInfo().limits().maxMultiDrawDirectInterleavedDrawCount();
            if (limit > 0) {
                cap = Math.min(cap, limit);
            }
        }
        return Math.max(1, cap);
    }

    private static String modeFor(final DeviceFeatures features) {
        if (features.drawIndirect() && features.multiDrawIndirect()) {
            return "indirect";
        }
        if (features.drawIndirect()) {
            return "indirect_single";
        }
        if (features.multiDrawDirectSeparate()) {
            return "multidraw_separate";
        }
        if (features.multiDrawDirectInterleaved()) {
            return "multidraw_interleaved";
        }
        if (features.nonZeroFirstInstance()) {
            return "base_instance_loop";
        }
        return "unsupported";
    }
}
