package dev.ultima.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered lines per scenario, written to disk and summarised by SHA-256. */
final class Digest {
    private final Map<String, List<String>> lines = new LinkedHashMap<>();

    void add(final String scenario, final String line) {
        this.lines.computeIfAbsent(scenario, key -> new ArrayList<>()).add(line);
    }

    int count(final String scenario) {
        return this.lines.getOrDefault(scenario, List.of()).size();
    }

    /** Writes one file per scenario plus the summary; returns the summary path. */
    Path write(final Path directory, final String label, final List<String> header) throws IOException {
        Files.createDirectories(directory);
        List<String> summary = new ArrayList<>(header);
        for (Map.Entry<String, List<String>> entry : this.lines.entrySet()) {
            String body = String.join("\n", entry.getValue());
            Files.writeString(directory.resolve(label + "." + entry.getKey() + ".txt"), body + "\n", StandardCharsets.UTF_8);
            summary.add("scenario " + entry.getKey() + " lines=" + entry.getValue().size() + " sha256=" + sha256(body));
        }
        Path summaryFile = directory.resolve(label + ".summary.txt");
        Files.writeString(summaryFile, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
        return summaryFile;
    }

    private static String sha256(final String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
