package dev.ultima.cache.iris;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Reuses one SHA-256 digest per thread. SHA-256 is required by every JVM. */
public final class Sha256 {
    private static final ThreadLocal<MessageDigest> DIGESTS = ThreadLocal.withInitial(Sha256::create);

    private Sha256() {
    }

    /** Reset digest for a caller that will feed it and then call {@link MessageDigest#digest()}. */
    public static MessageDigest acquire() {
        MessageDigest digest = DIGESTS.get();
        digest.reset();
        return digest;
    }

    public static byte[] digest(final byte[] bytes) {
        MessageDigest digest = acquire();
        return digest.digest(bytes);
    }

    private static MessageDigest create() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
