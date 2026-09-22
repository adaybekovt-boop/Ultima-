package dev.ultima;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build identity embedded into diagnostics and benchmark exports. */
public final class UltimaBuildInfo {
    private static final Properties VALUES = load();

    private UltimaBuildInfo() {
    }

    public static String version() {
        return VALUES.getProperty("version", "unknown");
    }

    public static String gitSha() {
        return VALUES.getProperty("gitSha", "unknown");
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = UltimaBuildInfo.class.getResourceAsStream("/ultima-build.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException | IllegalArgumentException ignored) {
        }
        return properties;
    }
}
