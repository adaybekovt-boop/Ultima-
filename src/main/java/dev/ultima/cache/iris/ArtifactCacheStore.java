package dev.ultima.cache.iris;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, checksummed, atomic persistent store for transformed shader source.
 *
 * <p>The directory is indexed once when this object is created. Reads use that bounded index and
 * never scan the directory. Payloads are loaded one entry at a time and are not retained by the
 * store after the caller consumes them.
 */
public final class ArtifactCacheStore {
    static final int MAGIC = 0x55494641; // UIFA
    public static final int SCHEMA_VERSION = 1;
    private static final int CHECKSUM_LENGTH = 32;
    private static final int HEADER_LENGTH = 4 + 4 + ArtifactKey.LENGTH + 8 + 8 + 4 + CHECKSUM_LENGTH;
    private static final int MAX_STAGE_COUNT = 8;
    private static final int MAX_STAGE_NAME_BYTES = 64;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final long TOUCH_INTERVAL_MILLIS = 60_000L;
    private static final String SUFFIX = ".uifa";

    private final Path directory;
    private final long maxBytes;
    private final int maxEntries;
    private final ArtifactCacheMetrics metrics;
    private final Object[] stripes = new Object[64];
    private final Map<String, EntryMeta> index = new HashMap<>();
    private long indexedBytes;

    public ArtifactCacheStore(
            final Path directory,
            final long maxBytes,
            final int maxEntries,
            final ArtifactCacheMetrics metrics) {
        this.directory = directory;
        this.maxBytes = Math.max(HEADER_LENGTH, maxBytes);
        this.maxEntries = Math.max(1, maxEntries);
        this.metrics = metrics == null ? new ArtifactCacheMetrics() : metrics;
        for (int index = 0; index < this.stripes.length; index++) {
            this.stripes[index] = new Object();
        }
        buildIndex();
    }

    public Optional<ShaderArtifact> read(final ArtifactKey key) {
        this.metrics.requests.increment();
        long started = System.nanoTime();
        synchronized (stripe(key)) {
            EntryMeta metadata;
            synchronized (this.index) {
                metadata = this.index.get(key.hex());
            }
            if (metadata == null) {
                this.metrics.misses.increment();
                this.metrics.cacheReadNanos.add(System.nanoTime() - started);
                return Optional.empty();
            }

            try {
                ShaderArtifact artifact = readEntry(metadata.path(), key);
                this.metrics.hits.increment();
                this.metrics.transformNanosSaved.add(artifact.transformNanos());
                touch(metadata);
                return Optional.of(artifact);
            } catch (CorruptEntryException exception) {
                this.metrics.corruptions.increment();
                invalidateLocked(key, metadata);
            } catch (IOException | SecurityException exception) {
                this.metrics.readFailures.increment();
            } finally {
                this.metrics.cacheReadNanos.add(System.nanoTime() - started);
            }
            this.metrics.misses.increment();
            return Optional.empty();
        }
    }

    public void write(final ArtifactKey key, final ShaderArtifact artifact) {
        long started = System.nanoTime();
        byte[] payload;
        try {
            payload = encodePayload(artifact.stages());
            if (payload.length > MAX_PAYLOAD_BYTES) {
                this.metrics.writeFailures.increment();
                return;
            }
        } catch (IOException | RuntimeException exception) {
            this.metrics.writeFailures.increment();
            return;
        }

        synchronized (stripe(key)) {
            Path temporary = null;
            try {
                Files.createDirectories(this.directory);
                temporary = Files.createTempFile(this.directory, key.hex() + ".", ".tmp");
                byte[] checksum = sha256(payload);
                ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
                header.putInt(MAGIC);
                header.putInt(SCHEMA_VERSION);
                header.put(key.bytes());
                header.putLong(System.currentTimeMillis());
                header.putLong(artifact.transformNanos());
                header.putInt(payload.length);
                header.put(checksum);
                header.flip();

                try (FileChannel channel = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    writeFully(channel, header);
                    writeFully(channel, ByteBuffer.wrap(payload));
                    channel.force(true);
                }

                Path target = pathFor(key);
                moveAtomically(temporary, target);
                temporary = null;
                long size = HEADER_LENGTH + (long)payload.length;
                synchronized (this.index) {
                    EntryMeta previous = this.index.put(
                            key.hex(), new EntryMeta(target, size, System.currentTimeMillis()));
                    if (previous != null) {
                        this.indexedBytes -= previous.size();
                    }
                    this.indexedBytes += size;
                }
                this.metrics.bytesWritten.add(size);
                cleanupIfNeeded(key.hex());
            } catch (IOException | RuntimeException exception) {
                this.metrics.writeFailures.increment();
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException | SecurityException ignored) {
                    }
                }
                this.metrics.cacheWriteNanos.add(System.nanoTime() - started);
            }
        }
    }

    public void invalidate(final ArtifactKey key) {
        synchronized (stripe(key)) {
            EntryMeta metadata;
            synchronized (this.index) {
                metadata = this.index.get(key.hex());
            }
            if (metadata != null) {
                invalidateLocked(key, metadata);
            }
        }
    }

    public ArtifactCacheMetrics.Snapshot snapshot() {
        synchronized (this.index) {
            return this.metrics.snapshot(this.indexedBytes, this.index.size());
        }
    }

    private ShaderArtifact readEntry(final Path path, final ArtifactKey expectedKey)
            throws IOException, CorruptEntryException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < HEADER_LENGTH) {
                throw new CorruptEntryException();
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
            readFully(channel, header);
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != SCHEMA_VERSION) {
                throw new CorruptEntryException();
            }
            byte[] storedKey = new byte[ArtifactKey.LENGTH];
            header.get(storedKey);
            if (!MessageDigest.isEqual(storedKey, expectedKey.bytes())) {
                throw new CorruptEntryException();
            }
            header.getLong(); // creation time, diagnostic only
            long transformNanos = header.getLong();
            int payloadLength = header.getInt();
            byte[] expectedChecksum = new byte[CHECKSUM_LENGTH];
            header.get(expectedChecksum);
            if (payloadLength < 0
                    || payloadLength > MAX_PAYLOAD_BYTES
                    || fileSize != HEADER_LENGTH + (long)payloadLength) {
                throw new CorruptEntryException();
            }

            byte[] payload = new byte[payloadLength];
            readFully(channel, ByteBuffer.wrap(payload));
            if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
                throw new CorruptEntryException();
            }
            this.metrics.bytesRead.add(fileSize);
            return new ShaderArtifact(decodePayload(payload), transformNanos);
        } catch (EOFException exception) {
            throw new CorruptEntryException();
        }
    }

    private void buildIndex() {
        try {
            Files.createDirectories(this.directory);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.directory, "*" + SUFFIX)) {
                for (Path path : entries) {
                    indexHeader(path);
                }
            }
            cleanupIfNeeded("");
        } catch (IOException | SecurityException exception) {
            this.metrics.readFailures.increment();
        }
    }

    private void indexHeader(final Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HEADER_LENGTH) {
                deleteInvalidStartupEntry(path, true);
                return;
            }
            ByteBuffer header = ByteBuffer.allocate(4 + 4 + ArtifactKey.LENGTH);
            readFully(channel, header);
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != SCHEMA_VERSION) {
                deleteInvalidStartupEntry(path, false);
                return;
            }
            byte[] key = new byte[ArtifactKey.LENGTH];
            header.get(key);
            String hex = new ArtifactKey(key).hex();
            if (!path.getFileName().toString().equals(hex + SUFFIX)) {
                deleteInvalidStartupEntry(path, true);
                return;
            }
            long lastUsed = Files.getLastModifiedTime(path).toMillis();
            this.index.put(hex, new EntryMeta(path, size, lastUsed));
            this.indexedBytes += size;
        } catch (IOException | SecurityException exception) {
            this.metrics.readFailures.increment();
        }
    }

    private void deleteInvalidStartupEntry(final Path path, final boolean corrupt) {
        if (corrupt) {
            this.metrics.corruptions.increment();
        } else {
            this.metrics.invalidations.increment();
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException ignored) {
        }
    }

    private void cleanupIfNeeded(final String protectedKey) {
        List<Map.Entry<String, EntryMeta>> oldest;
        synchronized (this.index) {
            if (this.index.size() <= this.maxEntries && this.indexedBytes <= this.maxBytes) {
                return;
            }
            oldest = new ArrayList<>(this.index.entrySet());
        }
        oldest.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsedMillis()));
        for (Map.Entry<String, EntryMeta> entry : oldest) {
            synchronized (this.index) {
                if (this.index.size() <= this.maxEntries && this.indexedBytes <= this.maxBytes) {
                    break;
                }
                if (entry.getKey().equals(protectedKey)) {
                    continue;
                }
                EntryMeta removed = this.index.remove(entry.getKey());
                if (removed == null) {
                    continue;
                }
                this.indexedBytes -= removed.size();
                try {
                    Files.deleteIfExists(removed.path());
                } catch (IOException | SecurityException ignored) {
                }
                this.metrics.invalidations.increment();
            }
        }
    }

    private void invalidateLocked(final ArtifactKey key, final EntryMeta expected) {
        synchronized (this.index) {
            EntryMeta current = this.index.get(key.hex());
            if (current == null || !current.path().equals(expected.path())) {
                return;
            }
            this.index.remove(key.hex());
            this.indexedBytes -= current.size();
        }
        try {
            Files.deleteIfExists(expected.path());
        } catch (IOException | SecurityException ignored) {
        }
        this.metrics.invalidations.increment();
    }

    private void touch(final EntryMeta metadata) {
        long now = System.currentTimeMillis();
        if (now - metadata.lastUsedMillis() < TOUCH_INTERVAL_MILLIS) {
            return;
        }
        try {
            Files.setLastModifiedTime(metadata.path(), FileTime.fromMillis(now));
        } catch (IOException | SecurityException ignored) {
        }
        synchronized (this.index) {
            this.index.replace(
                    fileKey(metadata.path()), metadata, new EntryMeta(metadata.path(), metadata.size(), now));
        }
    }

    private static String fileKey(final Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
    }

    private Object stripe(final ArtifactKey key) {
        return this.stripes[(key.firstByte() & 0xff) & (this.stripes.length - 1)];
    }

    private Path pathFor(final ArtifactKey key) {
        return this.directory.resolve(key.hex() + SUFFIX);
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] encodePayload(final Map<String, String> stages) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            List<Map.Entry<String, String>> ordered = stages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            if (ordered.size() > MAX_STAGE_COUNT) {
                throw new IOException("too many shader stages");
            }
            output.writeInt(ordered.size());
            for (Map.Entry<String, String> entry : ordered) {
                writeString(output, entry.getKey(), MAX_STAGE_NAME_BYTES);
                writeString(output, entry.getValue(), MAX_PAYLOAD_BYTES);
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> decodePayload(final byte[] payload)
            throws IOException, CorruptEntryException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_STAGE_COUNT) {
                throw new CorruptEntryException();
            }
            Map<String, String> stages = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String stage = readString(input, MAX_STAGE_NAME_BYTES);
                String source = readString(input, MAX_PAYLOAD_BYTES);
                if (stages.put(stage, source) != null) {
                    throw new CorruptEntryException();
                }
            }
            if (input.available() != 0) {
                throw new CorruptEntryException();
            }
            return stages;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new CorruptEntryException();
        }
    }

    private static void writeString(final DataOutputStream output, final String value, final int maxBytes)
            throws IOException {
        if (value == null) {
            throw new IOException("null artifact string");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IOException("artifact string too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(final DataInputStream input, final int maxBytes)
            throws IOException, CorruptEntryException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes || length > input.available()) {
            throw new CorruptEntryException();
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new CorruptEntryException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(final byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static void writeFully(final FileChannel channel, final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(final FileChannel channel, final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException();
            }
        }
    }

    private record EntryMeta(Path path, long size, long lastUsedMillis) {
    }

    private static final class CorruptEntryException extends Exception {
    }
}
