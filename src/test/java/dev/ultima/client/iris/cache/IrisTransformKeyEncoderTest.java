package dev.ultima.client.iris.cache;

import dev.ultima.cache.iris.ArtifactKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.parameter.SodiumParameters;

/** Exact-key contracts derived from the supported Iris TransformPatcher input graph. */
public final class IrisTransformKeyEncoderTest {
    private IrisTransformKeyEncoderTest() {
    }

    public static void main(final String[] args) {
        identicalInputsAndDifferentIterationOrderMatch();
        everyEnvironmentInputInvalidates();
        sourceAndPackDerivedInputsInvalidate();
        mutableTransformerScratchIsExcluded();
        unknownParameterImplementationFailsClosed();
    }

    private static void identicalInputsAndDifferentIterationOrderMatch() {
        Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> first = new LinkedHashMap<>();
        first.put(texture("normals", Parameters.TextureType.TEXTURE_2D), "normals");
        first.put(texture("noise", Parameters.TextureType.TEXTURE_3D), "noise");
        Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> reversed = new LinkedHashMap<>();
        reversed.put(texture("noise", Parameters.TextureType.TEXTURE_3D), "noise");
        reversed.put(texture("normals", Parameters.TextureType.TEXTURE_2D), "normals");
        ArtifactKey left = encode(parameters(first, Set.of("gaux1", "depthtex0")), sources("A"), environment());
        ArtifactKey right = encode(parameters(reversed, Set.of("depthtex0", "gaux1")), sources("A"), environment());
        require(left.equals(right), "map/set iteration order changed the key");
    }

    private static void everyEnvironmentInputInvalidates() {
        SodiumParameters parameters = parameters(defaultMap(), Set.of("depthtex0"));
        List<IrisTransformKeyEncoder.StageSource> sources = sources("A");
        IrisTransformKeyEncoder.Environment base = environment();
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment("adapter-v2", base.adapterFingerprint(), base.irisVersion(), base.minecraftVersion(), false, false)), "adapter revision");
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment(base.adapterId(), "different-jar", base.irisVersion(), base.minecraftVersion(), false, false)), "jar fingerprint");
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment(base.adapterId(), base.adapterFingerprint(), "1.11.5", base.minecraftVersion(), false, false)), "Iris version");
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment(base.adapterId(), base.adapterFingerprint(), base.irisVersion(), "26.3", false, false)), "Minecraft version");
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment(base.adapterId(), base.adapterFingerprint(), base.irisVersion(), base.minecraftVersion(), true, false)), "debug print mode");
        requireDifferent(encode(parameters, sources, base), encode(parameters, sources,
                new IrisTransformKeyEncoder.Environment(base.adapterId(), base.adapterFingerprint(), base.irisVersion(), base.minecraftVersion(), false, true)), "depth convention");
    }

    private static void sourceAndPackDerivedInputsInvalidate() {
        SodiumParameters base = parameters(defaultMap(), Set.of("depthtex0"));
        requireDifferent(encode(base, sources("A"), environment()), encode(base, sources("B"), environment()), "source");
        requireDifferent(encode(base, sources("A"), environment()), encode(
                parameters(defaultMap(), Set.of("depthtex1")), sources("A"), environment()), "texture override");
        Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> remapped = defaultMap();
        remapped.put(texture("normals", Parameters.TextureType.TEXTURE_2D), "different_sampler");
        requireDifferent(encode(base, sources("A"), environment()), encode(
                parameters(remapped, Set.of("depthtex0")), sources("A"), environment()), "texture mapping");
        SodiumParameters shadow = new SodiumParameters(
                defaultMap(), Set.of("depthtex0"), new AlphaTest(AlphaTest.Function.GREATER, 0.1f), true);
        requireDifferent(encode(base, sources("A"), environment()), encode(shadow, sources("A"), environment()), "shadow/alpha context");
    }

    private static void mutableTransformerScratchIsExcluded() {
        SodiumParameters parameters = parameters(defaultMap(), Set.of("depthtex0"));
        ArtifactKey before = encode(parameters, sources("A"), environment());
        parameters.type = Parameters.ShaderType.FRAGMENT;
        parameters.name = "mutated-by-transformer";
        ArtifactKey after = encode(parameters, sources("A"), environment());
        require(before.equals(after), "Iris transformer scratch polluted the key");
    }

    private static void unknownParameterImplementationFailsClosed() {
        Object unknown = new Object();
        ArtifactKey key = IrisTransformKeyEncoder.encode("graphics", "name", sources("A"), unknown, environment());
        require(key == null, "unknown parameter implementation produced a partial key");
    }

    private static ArtifactKey encode(
            final SodiumParameters parameters,
            final List<IrisTransformKeyEncoder.StageSource> sources,
            final IrisTransformKeyEncoder.Environment environment) {
        ArtifactKey key = IrisTransformKeyEncoder.encode("graphics", "gbuffers_terrain", sources, parameters, environment);
        require(key != null, "supported fixture was rejected");
        return key;
    }

    private static SodiumParameters parameters(
            final Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> map,
            final Set<String> overrides) {
        return new SodiumParameters(
                map, overrides, new AlphaTest(AlphaTest.Function.GREATER, 0.1f), false);
    }

    private static Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> defaultMap() {
        Map<Tri<String, Parameters.TextureType, Parameters.TextureStage>, String> map = new LinkedHashMap<>();
        map.put(texture("normals", Parameters.TextureType.TEXTURE_2D), "normals");
        return map;
    }

    private static Tri<String, Parameters.TextureType, Parameters.TextureStage> texture(
            final String name, final Parameters.TextureType type) {
        return new Tri<>(name, type, Parameters.TextureStage.GBUFFERS_AND_SHADOW);
    }

    private static List<IrisTransformKeyEncoder.StageSource> sources(final String marker) {
        return List.of(
                new IrisTransformKeyEncoder.StageSource("VERTEX", "#version 330\n//" + marker),
                new IrisTransformKeyEncoder.StageSource("GEOMETRY", null),
                new IrisTransformKeyEncoder.StageSource("TESS_CONTROL", null),
                new IrisTransformKeyEncoder.StageSource("TESS_EVAL", null),
                new IrisTransformKeyEncoder.StageSource("FRAGMENT", "#version 330\n//fragment"));
    }

    private static IrisTransformKeyEncoder.Environment environment() {
        return new IrisTransformKeyEncoder.Environment(
                "iris-transform-patcher-1.11.4-mc26.2-r1",
                "official-jar-and-class-fingerprint",
                "1.11.4+mc26.2",
                "26.2",
                false,
                false);
    }

    private static void requireDifferent(final ArtifactKey left, final ArtifactKey right, final String input) {
        require(!left.equals(right), input + " change did not invalidate the key");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
