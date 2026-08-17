package dev.ultima.server.metrics;

import dev.ultima.config.UltimaConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON export for a finished {@code /ultima profile} session. Field style matches the client
 * benchmark recorder so later A/B server runs can be compared with the same tooling habits.
 */
public final class ServerProfileJson {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-server-metrics");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private ServerProfileJson() {
    }

    public static Path write(final ServerProfileSession session) {
        if (session == null) {
            return null;
        }
        Path output = session.outputFile();
        if (output == null) {
            output = defaultOutputPath();
        }
        if (output == null) {
            LOGGER.warn("Could not resolve a server profile output path.");
            return null;
        }
        String json = render(session);
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, json, StandardCharsets.UTF_8);
            LOGGER.info("Wrote Ultima server profile JSON to {}", output.toAbsolutePath());
            return output;
        } catch (IOException | SecurityException e) {
            LOGGER.error("Could not write Ultima server profile JSON to {}", output.toAbsolutePath(), e);
            return null;
        }
    }

    public static String render(final ServerProfileSession session) {
        StringBuilder json = new StringBuilder(16_384);
        json.append("{\n");
        field(json, "schemaVersion", 1);
        comma(json);
        field(json, "kind", "ultima_server_profile");
        comma(json);
        field(json, "purpose", "diagnostic_not_optimization");
        comma(json);
        field(json, "stopReason", session.stopReason());
        comma(json);
        field(json, "requestedSeconds", session.requestedSeconds());
        comma(json);
        field(json, "ticksSampled", session.ticks().size());
        comma(json);
        field(json, "lagTicksOverThreshold", session.lagTicks());
        comma(json);
        field(json, "lagTickThresholdNs", ServerMetrics.lagTickThresholdNs());
        comma(json);
        field(json, "startedAtEpochMs", session.startedAtEpochMs());
        comma(json);
        field(json, "endedAtEpochMs", session.endedAtEpochMs());
        comma(json);
        field(json, "elapsedNs", session.elapsedNs());
        comma(json);
        field(
                json,
                "expectedOverhead",
                "Always-on: ~2 nanoTime calls + 1 atomic add per instrumented phase per interval, no allocations. Detailed mode allocates per-tick records only while /ultima profile is active.");
        comma(json);
        appendEnvironment(json);
        comma(json);
        appendModules(json);
        comma(json);
        appendCounters(json, session.snapshots());
        comma(json);
        appendTop(json, session.topTimeSubsystems(8));
        comma(json);
        appendTicks(json, session.ticks());
        json.append("\n}\n");
        return json.toString();
    }

    public static Path defaultOutputPath() {
        String override = System.getProperty("ultima.serverMetrics.output");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path dir;
        try {
            dir = FabricLoader.getInstance().getGameDir();
        } catch (Throwable ignored) {
            dir = Path.of(".");
        }
        String stamp = FILE_TIME.format(Instant.now());
        return dir.resolve("ultima-server-profile-" + stamp + ".json");
    }

    private static void appendEnvironment(final StringBuilder json) {
        json.append("  \"environment\": {\n");
        json.append("    \"minecraft\": ").append(quote(minecraftName())).append(",\n");
        json.append("    \"java\": ").append(quote(System.getProperty("java.runtime.version", ""))).append(",\n");
        json.append("    \"javaVm\": ").append(quote(System.getProperty("java.vm.name", ""))).append(",\n");
        json.append("    \"os\": ")
                .append(quote(System.getProperty("os.name", "") + " " + System.getProperty("os.arch", "")))
                .append(",\n");
        json.append("    \"maxMemoryBytes\": ").append(Runtime.getRuntime().maxMemory()).append(",\n");
        json.append("    \"playerCount\": ").append(ServerMetrics.playerCount()).append("\n");
        json.append("  }");
    }

    private static void appendModules(final StringBuilder json) {
        json.append("  \"modules\": [\n");
        try {
            List<UltimaConfig.ResolvedModule> modules = UltimaConfig.get().resolvedModules();
            for (int i = 0; i < modules.size(); i++) {
                UltimaConfig.ResolvedModule module = modules.get(i);
                json.append("    {\n")
                        .append("      \"key\": ").append(quote(module.key())).append(",\n")
                        .append("      \"requested\": ").append(module.requested()).append(",\n")
                        .append("      \"enabled\": ").append(module.enabled()).append(",\n")
                        .append("      \"enabledByDefault\": ").append(module.enabledByDefault()).append(",\n")
                        .append("      \"clientOnly\": ").append(module.clientOnly()).append(",\n")
                        .append("      \"moduleClass\": ").append(quote(module.moduleClass())).append("\n")
                        .append("    }");
                if (i + 1 < modules.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
        } catch (Throwable ignored) {
            json.append("    {\"key\": \"server_metrics\", \"enabled\": ")
                    .append(ServerMetrics.isEnabled())
                    .append(", \"moduleClass\": \"instrumentation\"}\n");
        }
        json.append("  ]");
    }

    private static void appendCounters(final StringBuilder json, final List<ServerMetrics.MetricSnapshot> snapshots) {
        json.append("  \"counters\": {\n");
        for (int i = 0; i < snapshots.size(); i++) {
            ServerMetrics.MetricSnapshot snapshot = snapshots.get(i);
            json.append("    ").append(quote(snapshot.id())).append(": {\n")
                    .append("      \"kind\": ").append(quote(snapshot.kind())).append(",\n")
                    .append("      \"histogram\": ").append(snapshot.histogram()).append(",\n")
                    .append("      \"last\": ").append(snapshot.last()).append(",\n")
                    .append("      \"windowSum\": ").append(snapshot.windowSum()).append(",\n")
                    .append("      \"windowCount\": ").append(snapshot.windowCount()).append(",\n")
                    .append("      \"windowAvg\": ").append(snapshot.windowAvg());
            if (snapshot.histogram()) {
                json.append(",\n      \"p50\": ").append(numberOrNull(snapshot.p50()))
                        .append(",\n      \"p95\": ").append(numberOrNull(snapshot.p95()))
                        .append(",\n      \"p99\": ").append(numberOrNull(snapshot.p99()))
                        .append(",\n      \"max\": ").append(numberOrNull(snapshot.max()));
            }
            json.append("\n    }");
            if (i + 1 < snapshots.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  }");
    }

    private static void appendTop(final StringBuilder json, final List<ServerMetrics.MetricSnapshot> top) {
        json.append("  \"topSubsystems\": [\n");
        for (int i = 0; i < top.size(); i++) {
            ServerMetrics.MetricSnapshot snapshot = top.get(i);
            json.append("    {")
                    .append("\"id\": ").append(quote(snapshot.id()))
                    .append(", \"windowSum\": ").append(snapshot.windowSum())
                    .append(", \"windowAvg\": ").append(snapshot.windowAvg())
                    .append('}');
            if (i + 1 < top.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void appendTicks(final StringBuilder json, final List<ServerMetrics.TickRecord> ticks) {
        json.append("  \"ticks\": [\n");
        MetricId[] metrics = MetricId.all();
        for (int t = 0; t < ticks.size(); t++) {
            ServerMetrics.TickRecord tick = ticks.get(t);
            json.append("    {\n")
                    .append("      \"tick\": ").append(tick.tick()).append(",\n")
                    .append("      \"playerCount\": ").append(tick.playerCount()).append(",\n")
                    .append("      \"totalNs\": ").append(tick.value(MetricId.TICK_TOTAL)).append(",\n")
                    .append("      \"phases\": {\n");
            boolean firstPhase = true;
            for (MetricId metric : metrics) {
                if (metric.kind() != MetricId.Kind.TIME || metric == MetricId.TICK_TOTAL) {
                    continue;
                }
                long value = tick.value(metric);
                if (value == 0L) {
                    continue;
                }
                if (!firstPhase) {
                    json.append(",\n");
                }
                firstPhase = false;
                json.append("        ").append(quote(metric.id())).append(": ").append(value);
            }
            json.append("\n      },\n")
                    .append("      \"otherNs\": ").append(otherNs(tick)).append(",\n")
                    .append("      \"events\": [");
            List<SemanticEvent> events = tick.events();
            for (int e = 0; e < events.size(); e++) {
                SemanticEvent event = events.get(e);
                if (e != 0) {
                    json.append(", ");
                }
                json.append("{\"kind\": ").append(quote(event.kind()))
                        .append(", \"message\": ").append(quote(event.message()))
                        .append('}');
            }
            json.append("]\n    }");
            if (t + 1 < ticks.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]");
    }

    /**
     * Server-thread remainder after subtracting nested-but-not-overlapping categories that are
     * known to sit inside {@code tick.total}. AI is omitted because it is already inside entities.
     */
    static long otherNs(final ServerMetrics.TickRecord tick) {
        long total = tick.value(MetricId.TICK_TOTAL);
        long accounted = tick.value(MetricId.TICK_ENTITIES)
                + tick.value(MetricId.TICK_BLOCK_ENTITIES)
                + tick.value(MetricId.TICK_RANDOM_TICKS)
                + tick.value(MetricId.TICK_CHUNK_MANAGER)
                + tick.value(MetricId.CHUNK_SEND_PREPARE);
        long other = total - accounted;
        return other < 0L ? 0L : other;
    }

    private static String minecraftName() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (Throwable ignored) {
            return "26.2";
        }
    }

    private static void comma(final StringBuilder json) {
        json.append(",\n");
    }

    private static void field(final StringBuilder json, final String key, final String value) {
        json.append("  ").append(quote(key)).append(": ").append(quote(value));
    }

    private static void field(final StringBuilder json, final String key, final long value) {
        json.append("  ").append(quote(key)).append(": ").append(value);
    }

    private static String numberOrNull(final Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    static String quote(final String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int)character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }
}
