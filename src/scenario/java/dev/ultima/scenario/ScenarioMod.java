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
        String workloadName = System.getProperty("ultima.scenario.bench");
        if (workloadName != null && !workloadName.isBlank()) {
            BenchWorkload workload = BenchWorkloads.all().get(workloadName);
            if (workload == null) {
                throw new IllegalArgumentException("unknown benchmark workload '" + workloadName
                        + "'; known: " + BenchWorkloads.all().keySet());
            }
            int warmup = Integer.getInteger("ultima.scenario.warmup", 600);
            int ticks = Integer.getInteger("ultima.scenario.ticks", 1200);
            BenchRunner bench = new BenchRunner(label, out, workload, warmup, ticks);
            ServerLifecycleEvents.SERVER_STARTED.register(bench::onStarted);
            ServerTickEvents.START_SERVER_TICK.register(bench::onStartTick);
            ServerTickEvents.END_SERVER_TICK.register(bench::onEndTick);
            return;
        }
        ScenarioRunner runner = new ScenarioRunner(label, out, Scenarios.all());
        ServerLifecycleEvents.SERVER_STARTED.register(runner::onStarted);
        ServerTickEvents.END_SERVER_TICK.register(runner::onTick);
    }
}
