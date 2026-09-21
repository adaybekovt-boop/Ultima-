package dev.ultima.cache.iris;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe disk payload: transformed source text only, never GL handles or a live AST. */
public record ShaderArtifact(Map<String, String> stages, long transformNanos) {
    public ShaderArtifact {
        stages = Map.copyOf(new LinkedHashMap<>(stages));
        transformNanos = Math.max(0L, transformNanos);
    }
}
