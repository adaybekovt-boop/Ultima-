package dev.ultima.cache.iris;

import java.util.Arrays;
import java.util.HexFormat;

/** SHA-256 identity of every input that can affect one Iris frontend transformation. */
public final class ArtifactKey {
    public static final int LENGTH = 32;

    private final byte[] bytes;
    private final String hex;

    public ArtifactKey(final byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("artifact key must be a SHA-256 digest");
        }
        this.bytes = bytes.clone();
        this.hex = HexFormat.of().formatHex(this.bytes);
    }

    public byte[] bytes() {
        return this.bytes.clone();
    }

    byte firstByte() {
        return this.bytes[0];
    }

    public String hex() {
        return this.hex;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ArtifactKey key && Arrays.equals(this.bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return this.hex;
    }
}
