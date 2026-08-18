package dev.ultima.server.metrics;

/**
 * One semantic cause recorded during opt-in detailed tracing. Not allocated on the always-on path.
 */
public record SemanticEvent(long tick, String kind, String message) {
}
