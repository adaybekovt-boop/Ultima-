package dev.ultima.client.benchmark;

import java.util.Collection;

/**
 * Minimal JSON fragment helpers for the opt-in client benchmark recorder.
 */
final class BenchmarkJson {
    private BenchmarkJson() {
    }

    static void comma(final StringBuilder json) {
        json.append(",\n");
    }

    static void field(final StringBuilder json, final String key, final String value) {
        json.append("  ").append(quote(key)).append(": ").append(quote(value));
    }

    static void field(final StringBuilder json, final String key, final boolean value) {
        json.append("  ").append(quote(key)).append(": ").append(value);
    }

    static void field(final StringBuilder json, final String key, final long value) {
        json.append("  ").append(quote(key)).append(": ").append(value);
    }

    static void field(final StringBuilder json, final String key, final double value) {
        json.append("  ").append(quote(key)).append(": ").append(value);
    }

    static void rawField(final StringBuilder json, final String key, final String raw) {
        json.append("  ").append(quote(key)).append(": ").append(raw);
    }

    static void stringArray(final StringBuilder json, final String key, final Collection<String> values) {
        json.append("  ").append(quote(key)).append(": [");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append(quote(value));
        }
        json.append(']');
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
                        escaped.append(String.format("\\u%04x", (int)character));
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
