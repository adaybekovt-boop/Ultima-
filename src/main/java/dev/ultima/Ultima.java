package dev.ultima;

import dev.ultima.cache.RegistryCacheLifecycle;
import dev.ultima.command.UltimaCommands;
import dev.ultima.config.UltimaConfig;
import dev.ultima.server.metrics.ServerMetrics;
import dev.ultima.sleeping.BlockEntitySleepRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Ultima implements ModInitializer {
    public static final String MOD_ID = "ultima";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Ultima initialized with {} of {} optimization modules enabled.",
                UltimaConfig.get().enabledModuleCount(), UltimaConfig.get().knownModuleCount());
        UltimaConfig.get().logResolvedModules();
        ServerMetrics.setEnabled(UltimaConfig.get().isEnabled(ServerMetrics.MODULE_KEY));
        RegistryCacheLifecycle.register();
        UltimaCommands.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BlockEntitySleepRuntime.clearAll());
        ServerLevelEvents.UNLOAD.register((server, level) -> BlockEntitySleepRuntime.clearLevel(level));
    }
}
