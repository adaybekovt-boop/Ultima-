package dev.ultima.meshing;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Section-level fail-open for the hybrid mesher. If the fast-path compile of
 * one section throws, that section is rebuilt with the vanilla supplier and
 * later sections are unchanged. Mirrors retained-terrain {@code failOpen}:
 * never crash the client for an Ultima-only compile fault.
 *
 * <p>Callers that already wrote into shared section-pack buffers must finish
 * and close those partial meshes before invoking the vanilla supplier.
 */
public final class MesherSectionFailOpen {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-mesher-fast-path");

    public record Outcome<T>(T value, boolean usedVanillaFallback, String error) {
        public boolean hybridSucceeded() {
            return !this.usedVanillaFallback;
        }
    }

    private MesherSectionFailOpen() {
    }

    public static <T> Outcome<T> compile(
            final Object sectionId,
            final Supplier<T> hybrid,
            final Supplier<T> vanilla) {
        try {
            return new Outcome<>(hybrid.get(), false, null);
        } catch (Throwable error) {
            log(sectionId, error);
            MesherMetrics.recordSectionFailure();
            return new Outcome<>(vanilla.get(), true, String.valueOf(error));
        }
    }

    public static void log(final Object sectionId, final Throwable error) {
        LOGGER.warn(
                "Mesher fast path failed open to vanilla for section {} ({})",
                sectionId,
                error == null ? "unknown" : error.toString(),
                error);
    }
}
