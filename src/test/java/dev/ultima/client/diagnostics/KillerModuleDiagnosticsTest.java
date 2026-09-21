package dev.ultima.client.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ultima.config.KillerModuleCompatibility;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ensures the on-demand diagnostic export remains valid JSON and complete while modules are off. */
public final class KillerModuleDiagnosticsTest {
    private KillerModuleDiagnosticsTest() {
    }

    public static void main(final String[] args) {
        Map<String, Boolean> requested = new LinkedHashMap<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            requested.put(module.key(), false);
        }
        UltimaConfig config = UltimaConfig.createForTests(requested);
        String json = KillerModuleDiagnostics.toJson(config);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        require(root.get("schemaVersion").getAsInt() == 1, "wrong diagnostics schema");
        require(root.getAsJsonObject("environment").has("ultimaGitSha"), "build SHA missing");
        require(root.getAsJsonArray("modules").size() == 3, "killer-module status count changed");
        require(root.has("artifactCache"), "artifact metrics missing");
        require(root.has("admissionBroker"), "broker metrics missing");
        require(root.has("renderWarmup"), "warmup metrics missing");
        for (var element : root.getAsJsonArray("modules")) {
            JsonObject module = element.getAsJsonObject();
            require(!module.get("active").getAsBoolean(), "off module reported active");
            require(module.getAsJsonObject("adapter").has("fingerprint"), "adapter fingerprint missing");
        }
        require(json.contains(KillerModuleCompatibility.IRIS_MODULE), "Iris cache status missing");
        require(json.contains(KillerModuleCompatibility.BROKER_MODULE), "broker status missing");
        require(json.contains(KillerModuleCompatibility.WARMUP_MODULE), "warmup status missing");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
