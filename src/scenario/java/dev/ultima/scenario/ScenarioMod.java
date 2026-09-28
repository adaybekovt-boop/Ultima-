package dev.ultima.scenario;

import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point. Does nothing unless {@code -Dultima.scenario.label} is set. */
public final class ScenarioMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-scenarios");

    @Override
    public void onInitialize() {
        String label = System.getProperty("ultima.scenario.label");
        LOGGER.info("Ultima scenario harness present, label={}", label);
        if (label == null || label.isBlank()) {
            return;
        }
        Path out = Path.of(System.getProperty("ultima.scenario.out", "build/scenarios"));
        ScenarioRunner runner = new ScenarioRunner(label, out, Scenarios.all());
        ServerLifecycleEvents.SERVER_STARTED.register(runner::onStarted);
        ServerTickEvents.END_SERVER_TICK.register(runner::onTick);
    }
}
