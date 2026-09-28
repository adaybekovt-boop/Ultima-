package dev.ultima.cache.iris;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Safe disk payload: transformed source text only, never GL handles or a live AST. */
public record ShaderArtifact(Map<String, String> stages, long transformNanos) {
    public ShaderArtifact {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(stages);
        if (copy.containsKey(null)) {
            throw new IllegalArgumentException("shader stage name cannot be null");
        }
        stages = Collections.unmodifiableMap(copy);
        transformNanos = Math.max(0L, transformNanos);
    }
}
