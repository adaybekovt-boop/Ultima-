package dev.ultima.cache.iris;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Disk-format and fail-open contract checks for the Iris artifact L2. */
public final class ArtifactCacheStoreTest {
    private ArtifactCacheStoreTest() {
    }

    public static void main(final String[] args) throws Exception {
        Path root = Files.createTempDirectory("ultima-artifact-cache-test-");
        try {
            coldMissThenWarmHit(root.resolve("warm"));
            sameJvmHotHitDoesNotRereadPayload(root.resolve("hot"));
            processRestartRetainsArtifact(root.resolve("restart"));
            keyMaterialChangesMiss(root.resolve("keys"));
            corruptedPayloadFallsBack(root.resolve("corrupt"));
            truncatedEntryFallsBack(root.resolve("truncated"));
            unknownSchemaFallsBack(root.resolve("schema"));
            writeFailureDoesNotEscape(root.resolve("write-failure"));
            readOnlyDirectoryDoesNotEscape(root.resolve("read-only"));
            parallelSameKeyCannotCorrupt(root.resolve("parallel"));
            boundedCleanup(root.resolve("bounded"));
            freshTemporarySurvivesStartup(root.resolve("fresh-tmp"));
            staleTemporaryIsRemoved(root.resolve("stale-tmp"));
        } finally {
            deleteTree(root);
        }
    }

    private static void coldMissThenWarmHit(final Path directory) {
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        ArtifactKey key = key("same-inputs");
        require(store.read(key).isEmpty(), "cold lookup must miss");
        ShaderArtifact expected = artifact("void main() {}", 123_456L);
        store.write(key, expected);
        ShaderArtifact actual = store.read(key).orElseThrow();
        require(actual.stages().equals(expected.stages()), "warm payload changed");
        require(actual.stages().containsKey("GEOMETRY") && actual.stages().get("GEOMETRY") == null,
                "absent shader stage marker changed");
        require(actual.transformNanos() == expected.transformNanos(), "transform estimate changed");
    }

    private static void sameJvmHotHitDoesNotRereadPayload(final Path directory) {
        ArtifactKey key = key("hot-path");
        store(directory, 1_000_000L, 32).write(key, artifact("hot", 7L));
        ArtifactCacheStore reopened = store(directory, 1_000_000L, 32);
        require(reopened.read(key).orElseThrow().transformNanos() == 7L, "first read missed the written artifact");
        require(reopened.payloadFileReads() == 1, "first read did not open the payload file");
        require(reopened.read(key).orElseThrow().transformNanos() == 7L, "second read missed the hot artifact");
        require(reopened.payloadFileReads() == 1, "same-JVM repeat reopened the payload file");
    }

    private static void keyMaterialChangesMiss(final Path directory) {
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        store.write(key("source=A;settings=1;iris=1.11.4;schema=1"), artifact("A", 1));
        require(store.read(key("source=B;settings=1;iris=1.11.4;schema=1")).isEmpty(), "source change hit");
        require(store.read(key("source=A;settings=2;iris=1.11.4;schema=1")).isEmpty(), "settings change hit");
        require(store.read(key("source=A;settings=1;iris=1.11.5;schema=1")).isEmpty(), "Iris revision change hit");
        require(store.read(key("source=A;settings=1;iris=1.11.4;schema=2")).isEmpty(), "schema change hit");
    }

    private static void processRestartRetainsArtifact(final Path directory) {
        ArtifactKey key = key("restart");
        ShaderArtifact expected = artifact("persistent", 44L);
        store(directory, 1_000_000L, 32).write(key, expected);
        ShaderArtifact reopened = store(directory, 1_000_000L, 32).read(key).orElseThrow();
        require(reopened.equals(expected), "reopened store lost or changed the persistent artifact");
    }

    private static void corruptedPayloadFallsBack(final Path directory) throws IOException {
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        ArtifactKey key = key("corrupt");
        store.write(key, artifact("payload", 10));
        Path file = entry(directory, key);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x5a;
        Files.write(file, bytes);
        ArtifactCacheStore restarted = store(directory, 1_000_000L, 32);
        require(restarted.read(key).isEmpty(), "corrupted payload was served");
        require(!Files.exists(file), "corrupted payload was not removed");
    }

    private static void truncatedEntryFallsBack(final Path directory) throws IOException {
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        ArtifactKey key = key("truncated");
        store.write(key, artifact("payload", 10));
        Path file = entry(directory, key);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(12);
        }
        require(store(directory, 1_000_000L, 32).read(key).isEmpty(), "truncated payload was served");
    }

    private static void unknownSchemaFallsBack(final Path directory) throws IOException {
        ArtifactCacheStore first = store(directory, 1_000_000L, 32);
        ArtifactKey key = key("unknown-schema");
        first.write(key, artifact("payload", 10));
        Path file = entry(directory, key);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(4);
            channel.write(ByteBuffer.allocate(4).putInt(999).flip());
        }
        ArtifactCacheStore restarted = store(directory, 1_000_000L, 32);
        require(restarted.read(key).isEmpty(), "unknown schema was indexed");
    }

    private static void writeFailureDoesNotEscape(final Path directory) throws IOException {
        Files.createDirectories(directory.getParent());
        Files.writeString(directory, "not a directory", StandardCharsets.UTF_8);
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        store.write(key("write-failure"), artifact("safe fallback", 1));
        require(store.snapshot().writeFailures() > 0L, "write failure was not recorded");
    }

    private static void readOnlyDirectoryDoesNotEscape(final Path directory) throws IOException {
        Files.createDirectories(directory);
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException exception) {
            return;
        }
        try {
            ArtifactCacheStore store = store(directory, 1_000_000L, 32);
            ArtifactKey key = key("read-only");
            store.write(key, artifact("fallback", 1L));
            // Privileged CI users may still write. Either outcome must remain a valid read/miss path.
            store.read(key);
        } finally {
            Files.setPosixFilePermissions(directory, original);
        }
    }

    private static void parallelSameKeyCannotCorrupt(final Path directory) throws Exception {
        ArtifactCacheStore store = store(directory, 1_000_000L, 32);
        ArtifactKey key = key("parallel");
        ShaderArtifact expected = artifact("parallel-payload", 77);
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int worker = 0; worker < workers; worker++) {
            executor.execute(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < 25; iteration++) {
                        store.write(key, expected);
                        store.read(key).orElseThrow();
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        require(executor.awaitTermination(30, TimeUnit.SECONDS), "parallel test timed out");
        if (failure.get() != null) {
            throw new AssertionError("parallel cache operation failed", failure.get());
        }
        require(store.read(key).orElseThrow().stages().equals(expected.stages()), "parallel entry corrupted");
    }

    private static void freshTemporarySurvivesStartup(final Path directory) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "in-flight-", ".tmp");
        Files.setLastModifiedTime(temporary, FileTime.fromMillis(System.currentTimeMillis()));
        store(directory, 1_000_000L, 32);
        require(Files.exists(temporary), "a young temporary was deleted at startup");
    }

    private static void staleTemporaryIsRemoved(final Path directory) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "abandoned-", ".tmp");
        Files.setLastModifiedTime(temporary, FileTime.fromMillis(System.currentTimeMillis() - 120_000L));
        store(directory, 1_000_000L, 32);
        require(!Files.exists(temporary), "a stale temporary survived startup");
    }

    private static void boundedCleanup(final Path directory) {
        ArtifactCacheStore store = store(directory, 1_000_000L, 2);
        store.write(key("one"), artifact("1", 1));
        store.write(key("two"), artifact("2", 1));
        store.write(key("three"), artifact("3", 1));
        require(store.snapshot().entries() <= 2, "entry bound exceeded");
    }

    private static ArtifactCacheStore store(final Path directory, final long bytes, final int entries) {
        return new ArtifactCacheStore(directory, bytes, entries, new ArtifactCacheMetrics());
    }

    private static ShaderArtifact artifact(final String source, final long nanos) {
        Map<String, String> stages = new java.util.LinkedHashMap<>();
        stages.put("VERTEX", source);
        stages.put("GEOMETRY", null);
        stages.put("TESS_CONTROL", null);
        stages.put("TESS_EVAL", null);
        stages.put("FRAGMENT", source + "//fragment");
        return new ShaderArtifact(stages, nanos);
    }

    private static ArtifactKey key(final String material) {
        try {
            return new ArtifactKey(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Path entry(final Path directory, final ArtifactKey key) {
        return directory.resolve(key.hex() + ".uifa");
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
