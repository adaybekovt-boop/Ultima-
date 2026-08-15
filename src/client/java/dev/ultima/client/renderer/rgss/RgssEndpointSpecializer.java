package dev.ultima.client.renderer.rgss;

/**
 * Exact-endpoint specialization of vanilla RGSS. At {@code blendFactor == 0}
 * only nearest is evaluated; at {@code blendFactor == 1} only RGSS is
 * evaluated. Intermediate blends are unchanged.
 */
public final class RgssEndpointSpecializer {
    private static final String MARKER = "ultima_rgss_endpoint";
    private static final String BLEND =
            "    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);";
    private static final String NEAREST_MIX = """
            vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

        return mix(nearestColor, rgssColor, blendFactor);""";

    private RgssEndpointSpecializer() {
    }

    public static String specialize(final String source) {
        if (source == null || source.contains(MARKER) || !source.contains(BLEND) || !source.contains("sampleRGSS")) {
            return source;
        }
        String withEarlyOut = source.replace(
                BLEND,
                BLEND + "\n    // " + MARKER + "\n    if (blendFactor <= 0.0) {\n"
                        + "        return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n"
                        + "    }");
        if (withEarlyOut.equals(source) || !withEarlyOut.contains(NEAREST_MIX)) {
            return source;
        }
        return withEarlyOut.replace(
                NEAREST_MIX,
                "if (blendFactor >= 1.0) {\n        return rgssColor;\n    }\n    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n\n    return mix(nearestColor, rgssColor, blendFactor);");
    }
}
