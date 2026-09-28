package dev.ultima.scenario;

import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Entry point. Does nothing unless {@code -Dultima.scenario.label} is set. */
public final class ScenarioMod implements ModInitializer {
    @Override
    public void onInitialize() {
        String label = System.getProperty("ultima.scenario.label");
        if (label == null || label.isBlank()) {
            return;
        }
        Path out = Path.of(System.getProperty("ultima.scenario.out", "build/scenarios"));
        ScenarioRunner runner = new ScenarioRunner(label, out, Scenarios.all());
        ServerLifecycleEvents.SERVER_STARTED.register(runner::onStarted);
        ServerTickEvents.END_SERVER_TICK.register(runner::onTick);
    }
}
