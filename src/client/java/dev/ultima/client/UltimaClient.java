package dev.ultima.client;

import dev.ultima.client.command.UltimaClientCommands;
import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrIrisCapabilities;
import dev.ultima.fsr.FsrLatencyContract;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UltimaClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ultima-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info(
                "Ultima client initialized with {} of {} modules enabled in this environment.",
                UltimaConfig.get().enabledModuleCount(),
                UltimaConfig.get().knownModuleCount());
        if (UltimaConfig.get().isEnabled("temporal")) {
            TemporalPipeline.get().initialize();
        }
        if (UltimaConfig.get().isRequested("fsr_upscaling")) {
            var fsr = UltimaConfig.get().resolve("fsr_upscaling");
            var settings = UltimaConfig.get().fsrSettings().resolved();
            LOGGER.info(
                    "Ultima FSR1 spatial upscaling requested={} enabled={} reason={} preset={} sharpnessStops={}",
                    fsr.requested(),
                    fsr.enabled(),
                    fsr.reason(),
                    settings.presetKey(),
                    settings.sharpnessStops());
            if (!fsr.enabled()) {
                LOGGER.info("FSR1 is inactive: {}", fsr.detail());
            }
            if (FsrIrisCapabilities.isIrisModLoaded()) {
                LOGGER.info(
                        "FSR1 Iris capability probe: {} addedGpuPass={} extraDisplayedFrames={} holdsFrame={}",
                        FsrIrisCapabilities.summary(),
                        FsrLatencyContract.addsGpuPassWhenIrisPresent(),
                        FsrLatencyContract.extraDisplayedFramesOfDelay(),
                        FsrLatencyContract.holdsCurrentFrameForInterpolation());
            }
        }
        UltimaClientCommands.register();
    }
}
