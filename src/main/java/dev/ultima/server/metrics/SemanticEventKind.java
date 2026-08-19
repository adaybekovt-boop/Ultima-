package dev.ultima.server.metrics;

/**
 * Named reasons that distinguish Ultima server telemetry from a generic JVM profiler.
 * Future optimizations should emit these instead of inventing parallel logs.
 */
public enum SemanticEventKind {
    HOPPER_WAKE_ADJACENT_INVENTORY(
            "hopper_wake_adjacent_inventory",
            "hopper woke because adjacent inventory changed"),
    TRACKER_RECALC_SECTION_BOUNDARY(
            "tracker_recalc_section_boundary",
            "entity tracker recalculated visibility because player crossed section boundary"),
    CHUNK_SERIALIZE_CACHE_MISS(
            "chunk_serialize_cache_miss",
            "chunk serialization cache missed because block state version changed"),
    PLAYER_ENTERED_CHUNKS("player_entered_chunks", "player moved into new chunks"),
    CHUNKS_GENERATED("chunks_generated", "chunks generated"),
    PACKETS_PREPARED("packets_prepared", "chunk packets prepared"),
    CUSTOM("custom", "");

    private final String id;
    private final String defaultMessage;

    SemanticEventKind(final String id, final String defaultMessage) {
        this.id = id;
        this.defaultMessage = defaultMessage;
    }

    public String id() {
        return this.id;
    }

    public String defaultMessage() {
        return this.defaultMessage;
    }
}
