package dev.ultima.config;

import java.util.List;
import java.util.function.Predicate;

/**
 * Known world-renderer families. Detection is an exact mod id or a canonical entry class.
 * An unrecognized mod id is not treated as a renderer.
 */
public final class RendererFamilyEvidence {
    public static final List<String> KNOWN_IDS = List.of("sodium", "iris", "canvas");
    static final String SODIUM_ENTRY = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager";
    static final String IRIS_ENTRY = "net.irisshaders.iris.pipeline.transform.TransformPatcher";

    private RendererFamilyEvidence() {
    }

    public static boolean conflicts() {
        return conflicts(LoadedModCache::isLoaded, RendererFamilyEvidence::classPresent);
    }

    static boolean conflicts(final Predicate<String> modLoaded, final Predicate<String> classPresent) {
        for (String id : KNOWN_IDS) {
            if (modLoaded.test(id)) {
                return true;
            }
        }
        return classPresent.test(SODIUM_ENTRY) || classPresent.test(IRIS_ENTRY);
    }

    private static volatile Boolean sodiumClass;
    private static volatile Boolean irisClass;

    private static boolean classPresent(final String binaryName) {
        if (SODIUM_ENTRY.equals(binaryName)) {
            Boolean cached = sodiumClass;
            if (cached == null) {
                cached = probeClass(binaryName);
                sodiumClass = cached;
            }
            return cached;
        }
        if (IRIS_ENTRY.equals(binaryName)) {
            Boolean cached = irisClass;
            if (cached == null) {
                cached = probeClass(binaryName);
                irisClass = cached;
            }
            return cached;
        }
        return probeClass(binaryName);
    }

    private static boolean probeClass(final String binaryName) {
        try {
            Class.forName(binaryName, false, RendererFamilyEvidence.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
