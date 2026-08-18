package dev.ultima.server.metrics;

/**
 * Server subsystem counters. Time metrics are nanoseconds. Count/gauge metrics are raw values.
 *
 * <p>Time categories are <em>not</em> a partition of {@link #TICK_TOTAL}: entity time includes AI,
 * and worldgen/IO/network often run on worker or Netty threads outside the server tick.
 */
public enum MetricId {
    TICK_TOTAL("tick.total", Kind.TIME, true),
    TICK_ENTITIES("tick.entities", Kind.TIME, false),
    TICK_BLOCK_ENTITIES("tick.block_entities", Kind.TIME, false),
    TICK_AI("tick.ai", Kind.TIME, false),
    TICK_RANDOM_TICKS("tick.random_ticks", Kind.TIME, false),
    TICK_CHUNK_MANAGER("tick.chunk_manager", Kind.TIME, false),

    CHUNK_LOAD("chunk.load", Kind.TIME, false),
    CHUNK_IO("chunk.io", Kind.TIME, false),
    CHUNK_GENERATE("chunk.generate", Kind.TIME, true),
    CHUNK_SERIALIZE("chunk.serialize", Kind.TIME, false),
    CHUNK_SEND_PREPARE("chunk.send_prepare", Kind.TIME, true),

    NETWORK_ENCODE("network.encode", Kind.TIME, true),
    NETWORK_COMPRESS("network.compress", Kind.TIME, true),
    NETWORK_ENCRYPT("network.encrypt", Kind.TIME, false),
    NETWORK_QUEUED_BYTES("network.queued_bytes", Kind.GAUGE, false),

    TRACKING_ENTITY_CANDIDATES("tracking.entity_candidates", Kind.COUNT, false),
    TRACKING_ADDED("tracking.added", Kind.COUNT, false),
    TRACKING_REMOVED("tracking.removed", Kind.COUNT, false),

    BE_SLEEPING("be.sleeping", Kind.GAUGE, false),
    BE_WAKEUPS("be.wakeups", Kind.COUNT, false),
    BE_THRASHES("be.thrashes", Kind.COUNT, false),

    AI_CLEAN_SKIPS("ai.clean_skips", Kind.COUNT, false),
    AI_INVALIDATIONS("ai.invalidations", Kind.COUNT, false),

    PLAYER_CHUNKS_ENTERED("tracking.player_chunks_entered", Kind.COUNT, false),
    CHUNKS_GENERATED("chunk.generated_count", Kind.COUNT, false),
    PACKETS_PREPARED_BYTES("chunk.packets_prepared_bytes", Kind.COUNT, false);

    public enum Kind {
        TIME,
        COUNT,
        GAUGE
    }

    private static final MetricId[] ALL = values();

    private final String id;
    private final Kind kind;
    private final boolean histogram;

    MetricId(final String id, final Kind kind, final boolean histogram) {
        this.id = id;
        this.kind = kind;
        this.histogram = histogram;
    }

    public String id() {
        return this.id;
    }

    public Kind kind() {
        return this.kind;
    }

    public boolean histogram() {
        return this.histogram;
    }

    public static MetricId[] all() {
        return ALL;
    }

    public static MetricId byId(final String id) {
        for (MetricId metric : ALL) {
            if (metric.id.equals(id)) {
                return metric;
            }
        }
        return null;
    }
}
