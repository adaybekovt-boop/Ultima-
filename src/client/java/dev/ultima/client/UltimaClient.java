package dev.ultima.client;

import dev.ultima.config.UltimaConfig;
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
    }
}
