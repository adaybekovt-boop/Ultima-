package dev.ultima.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fail-closed adapter selection for the three experimental killer modules.
 *
 * <p>External scheduler/transformer Mixins are enabled only for an exact published mod version
 * plus SHA-256 fingerprints of both the complete original jar and the integration class. A fork
 * that reuses the version string therefore does not silently inherit an adapter written for
 * different bytecode or transformer globals.
 */
public final class KillerModuleCompatibility {
    public static final String IRIS_MODULE = "iris_shader_frontend_artifact_cache";
    public static final String BROKER_MODULE = "cross_pipeline_admission_broker";
    public static final String WARMUP_MODULE = "render_warmup_system";

    private static final AdapterSpec IRIS = new AdapterSpec(
            "iris",
            "1.11.4+mc26.2",
            "net/irisshaders/iris/pipeline/transform/TransformPatcher.class",
            "101fb251c68034e4c8346de1c550616059d896dbfbb3fae56e5982a89877c25f",
            "f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c",
            "iris-transform-patcher-1.11.4-mc26.2-r1");
    private static final AdapterSpec SODIUM = new AdapterSpec(
            "sodium",
            "0.9.2+mc26.2",
            "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.class",
            "5a409c73d5e4a30853a5b4896db8610607d07b3a111b2cf80ffc82a81d11b443",
            "16a5e91db49750f8c046ecca3b8f2af5a28ea6f9b6581b42f123ca8fda20864f",
            "sodium-render-section-manager-0.9.2-mc26.2-r1");

    private static volatile AdapterState irisState;
    private static volatile AdapterState sodiumState;

    private KillerModuleCompatibility() {
    }

    public static boolean isSupported(final String module) {
        return state(module).supported();
    }

    public static AdapterState state(final String module) {
        return switch (module) {
            case IRIS_MODULE -> iris();
            case BROKER_MODULE -> sodium();
            case WARMUP_MODULE -> new AdapterState(
                    true,
                    "builtin-profiler-v1",
                    "minecraft",
                    minecraftVersion(),
                    "builtin",
                    "Built-in state-safe profiler and warmup scheduler are available.");
            default -> new AdapterState(true, "not_applicable", "", "", "", "No external adapter required.");
        };
    }

    public static boolean isKillerModule(final String module) {
        return IRIS_MODULE.equals(module) || BROKER_MODULE.equals(module) || WARMUP_MODULE.equals(module);
    }

    private static AdapterState iris() {
        AdapterState local = irisState;
        if (local == null) {
            synchronized (KillerModuleCompatibility.class) {
                local = irisState;
                if (local == null) {
                    local = probe(IRIS);
                    irisState = local;
                }
            }
        }
        return local;
    }

    private static AdapterState sodium() {
        AdapterState local = sodiumState;
        if (local == null) {
            synchronized (KillerModuleCompatibility.class) {
                local = sodiumState;
                if (local == null) {
                    local = probe(SODIUM);
                    sodiumState = local;
                }
            }
        }
        return local;
    }

    private static AdapterState probe(final AdapterSpec spec) {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            ModContainer container = loader.getModContainer(spec.modId()).orElse(null);
            if (container == null) {
                return unsupported(spec, "mod_absent", "Required mod '" + spec.modId() + "' is not loaded.", "", "");
            }

            String version = container.getMetadata().getVersion().getFriendlyString();
            if (!spec.version().equals(version)) {
                return unsupported(
                        spec,
                        "unsupported_version",
                        "Adapter supports " + spec.modId() + " " + spec.version() + "; found " + version + ".",
                        version,
                        "");
            }

            Fingerprint fingerprint = fingerprint(container, spec.classEntry());
            if (fingerprint == null) {
                return unsupported(
                        spec,
                        "fingerprint_unavailable",
                        "Could not read the original " + spec.modId() + " integration class; adapter remains off.",
                        version,
                        "");
            }
            if (!spec.classSha256().equals(fingerprint.classSha256())
                    || !spec.jarSha256().equals(fingerprint.jarSha256())) {
                return unsupported(
                        spec,
                        "unknown_implementation",
                        "The " + spec.modId() + " integration class fingerprint is not recognized; adapter remains off.",
                        version,
                        fingerprint.description());
            }
            return new AdapterState(
                    true,
                    spec.adapterId(),
                    spec.modId(),
                    version,
                    fingerprint.description(),
                    "Exact version, whole-jar fingerprint, and integration-class fingerprint matched.");
        } catch (Throwable throwable) {
            return unsupported(
                    spec,
                    "probe_failure",
                    "Adapter probe failed closed: " + throwable.getClass().getSimpleName() + ".",
                    "",
                    "");
        }
    }

    private static AdapterState unsupported(
            final AdapterSpec spec,
            final String reason,
            final String detail,
            final String version,
            final String fingerprint) {
        return new AdapterState(false, reason, spec.modId(), version, fingerprint, detail);
    }

    private static @org.jspecify.annotations.Nullable Fingerprint fingerprint(
            final ModContainer container, final String classEntry) throws IOException, NoSuchAlgorithmException {
        List<Path> paths = container.getOrigin().getPaths();
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (ZipFile jar = new ZipFile(path.toFile())) {
                ZipEntry entry = jar.getEntry(classEntry);
                if (entry == null) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                    return new Fingerprint(
                            HexFormat.of().formatHex(digest.digest()),
                            hashFile(path));
                }
            }
        }
        return null;
    }

    private static String hashFile(final Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String minecraftVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private record AdapterSpec(
            String modId,
            String version,
            String classEntry,
            String classSha256,
            String jarSha256,
            String adapterId) {
    }

    private record Fingerprint(String classSha256, String jarSha256) {
        String description() {
            return "jar=" + this.jarSha256 + ";class=" + this.classSha256;
        }
    }

    public record AdapterState(
            boolean supported,
            String state,
            String modId,
            String version,
            String fingerprint,
            String detail) {
    }
}
