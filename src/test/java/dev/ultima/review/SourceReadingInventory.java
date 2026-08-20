package dev.ultima.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Honest inventory of tests that read source text instead of (or in addition to) running
 * production behavior. Round 4 requires this list; new {@code Files.readString} uses in
 * {@code src/test} must be catalogued here.
 *
 * <p>Dangerous = the grep sits next to a claim about production behavior and can stay green
 * while that behavior is missing. Wiring = checking config/annotations/assets, with
 * separate behavioral coverage elsewhere (or no runtime equivalent in JavaExec).
 */
public final class SourceReadingInventory {
    public enum Kind {
        DANGEROUS,
        WIRING
    }

    public record Entry(String testClass, String whatItReads, Kind kind, String why) {}

    static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "dev.ultima.failopen.Wave2FailOpenTest",
                    "recipe/tag/state/slot-mask/entity-query Mixin + helper sources",
                    Kind.WIRING,
                    "DANGEROUS in round 3: grepped Mixins while a test copy (mixinGetRecipeFor) claimed "
                            + "production fail-open coverage. Round 4 recipe/brewing behavior is "
                            + "RecipeCacheDoors (the Mixin body). Slot-mask occupancy behavior is "
                            + "SlotMaskQueries.filterOccupiedPreservingOrder. Remaining greps only require "
                            + "Mixins to call those helpers, or keep a local catch door on tag/state/"
                            + "entity-query Mixins that this round did not rewrite. Not a live Mixin apply."),
            new Entry(
                    "dev.ultima.review.SlotMaskEntityQueryTest",
                    "VanillaInventoryMutationSources strings + Mixin method names via reflection",
                    Kind.WIRING,
                    "DANGEROUS if treated as mutation-coverage proof. Catalog/reflection is mixin-wiring "
                            + "only. replaceWith / unpack / indexed-write exception-after-mutation have "
                            + "separate behavioral tests on SlotMaskHooks. Live Inventory.replaceWith / "
                            + "LootTable.fill / Mixin apply are not invoked in JavaExec."),
            new Entry(
                    "dev.ultima.review.FsrUpscalingChecks",
                    "no Files.readString remaining; @Mixin priority + @Inject(resize) via reflection; "
                            + "Iris gate via UltimaMixinPlugin.shouldApplyMixin / FsrCompatibility.blocks",
                    Kind.WIRING,
                    "Earlier revisions grepped javadoc/CHANGELOG (unacceptable as behavior). Current "
                            + "priority/resize checks are annotation contracts. Iris skip is plugin+"
                            + "compatibility runtime. Not a live GameRenderer.resize."),
            new Entry(
                    "dev.ultima.review.MesherFastPathChecks",
                    "no Files.readString remaining; benchmark scene warmup/sample/camera via "
                            + "ClientFrameBenchmark public helpers",
                    Kind.WIRING,
                    "Earlier revisions grepped ClientFrameBenchmark source for scene wiring. Current "
                            + "checks call warmupFramesForScene/sampleFramesForScene/cameraModeForScene. "
                            + "Mesh equivalence cases are behavioral. Hardware FPS is not measured here."),
            new Entry(
                    "dev.ultima.review.ServerTelemetryChecks",
                    "server_metrics Mixin require=0 on Lithium-fragile INVOKE targets",
                    Kind.WIRING,
                    "require=0 is a Mixin apply contract; Lithium is not loaded in JavaExec. Cannot prove "
                            + "the inject still matches at runtime without a Lithium game test. Counter/"
                            + "percentile/gating cases are separate runtime checks."),
            new Entry(
                    "dev.ultima.review.VanillaClientHostingChecks",
                    "fabric.mod.json, mixin json, Java sources for forbidden network tokens",
                    Kind.WIRING,
                    "Hosting contract is configuration: no custom packets, side-split Mixins. Scanning "
                            + "sources for Fabric networking APIs is the check, not a substitute for a LAN join."),
            new Entry(
                    "dev.ultima.sleeping.HopperSleepEquivalenceTest",
                    "Ultima.java SERVER_STOPPED / UNLOAD registration",
                    Kind.WIRING,
                    "clearAll/clearLevel behavior is tested. Fabric event registration cannot be fired "
                            + "without a server. Source read only confirms onInitialize still subscribes."),
            new Entry(
                    "dev.ultima.review.RetainedFoundationChecks",
                    "GLSL shader sources (gl_DrawID ban, gl_BaseInstanceARB, Mojang import)",
                    Kind.WIRING,
                    "Shader text is the artifact under test; shaderc portability snippets compile when "
                            + "the native lib is present. Full pipeline compile is in-game."));

    private SourceReadingInventory() {
    }

    public static void assertCatalogCoversThisSuite() {
        List<Path> uncatalogued = new ArrayList<>();
        Path testRoot = Path.of("src/test/java");
        try (Stream<Path> walk = Files.walk(testRoot)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source;
                try {
                    source = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("could not read " + path, e);
                }
                if (!source.contains("Files.readString") && !source.contains("readSource(") && !source.contains("readUtf8(")
                        && !source.contains("readShader(")) {
                    return;
                }
                String className = classNameOf(testRoot, path);
                if (className.equals(SourceReadingInventory.class.getName())) {
                    return;
                }
                boolean listed = false;
                for (Entry entry : ENTRIES) {
                    if (entry.testClass().equals(className)) {
                        listed = true;
                        break;
                    }
                }
                if (!listed) {
                    uncatalogued.add(path);
                }
            });
        } catch (IOException e) {
            throw new AssertionError("could not walk test sources", e);
        }
        if (!uncatalogued.isEmpty()) {
            throw new AssertionError(
                    "new source-reading test must be catalogued in SourceReadingInventory: " + uncatalogued);
        }
        System.out.println("Source-reading test inventory (round 4):");
        for (Entry entry : ENTRIES) {
            System.out.println("  [" + entry.kind() + "] " + entry.testClass() + " — " + entry.whatItReads());
            System.out.println("      " + entry.why());
        }
    }

    private static String classNameOf(final Path testRoot, final Path path) {
        String relative = testRoot.relativize(path).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }
}
