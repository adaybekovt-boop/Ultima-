package dev.ultima.client.iris.cache;

import dev.ultima.cache.iris.ArtifactKey;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Key contracts checked against pinned Iris 1.11.4 class files, not a hand-extended stub. */
public final class IrisTransformKeyEncoderTest {
    private IrisTransformKeyEncoderTest() {
    }

    public static void main(final String[] args) throws Exception {
        pinnedParametersOmitTextureOverrides();
        realSodiumParametersBuildKey();
        mapIterationOrderDoesNotChangeKey();
        nullStageDiffersFromEmptyStage();
        environmentAndContextInputsInvalidate();
        mutableTransformerScratchIsExcluded();
        tamperedParametersFailClosed();
        unknownParameterImplementationFailsClosed();
    }

    private static void pinnedParametersOmitTextureOverrides() throws Exception {
        byte[] bytes = Iris1114Fixtures.resource(
                "net/irisshaders/iris/pipeline/transform/parameter/Parameters.class");
        require("b702e597b7c1c1b264f888a6de700016da1134c3249c78520ec74b7571a8db6c".equals(sha256(bytes)),
                "Parameters fixture is not the pinned Iris 1.11.4 class");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (FieldNode field : node.fields) {
            require(!"textureOverrides".equals(field.name), "pinned Parameters still has no textureOverrides field");
        }
        require(node.fields.stream().anyMatch(field -> "textureMap".equals(field.name)), "textureMap missing");
        require(node.fields.stream().anyMatch(field -> "patch".equals(field.name)), "patch missing");
    }

    private static void realSodiumParametersBuildKey() throws Exception {
        ClassLoader loader = Iris1114Fixtures.loader();
        Object parameters = sodium(loader, "normals", "normals", false);
        ArtifactKey key = encode(parameters, sources("A"), environment(false));
        require(key != null, "real Iris 1.11.4 SodiumParameters did not build a key");
        require(IrisTransformKeyEncoder.supportedStructure(parameters), "real parameter structure was rejected");
    }

    private static void mapIterationOrderDoesNotChangeKey() throws Exception {
        ClassLoader loader = Iris1114Fixtures.loader();
        Object first = orderedMap(loader, "normals", "noise");
        Object reversed = orderedMap(loader, "noise", "normals");
        Object left = Iris1114Fixtures.newSodiumParameters(
                loader, first, Iris1114Fixtures.alpha(loader, "GREATER", 0.1f), false);
        Object right = Iris1114Fixtures.newSodiumParameters(
                loader, reversed, Iris1114Fixtures.alpha(loader, "GREATER", 0.1f), false);
        require(encode(left, sources("A"), environment(false)).equals(encode(right, sources("A"), environment(false))),
                "texture map iteration order changed the key");
    }

    private static void nullStageDiffersFromEmptyStage() throws Exception {
        Object parameters = sodium(Iris1114Fixtures.loader(), "normals", "normals", false);
        ArtifactKey absent = encode(parameters, List.of(
                new IrisTransformKeyEncoder.StageSource("FRAGMENT", "frag"),
                new IrisTransformKeyEncoder.StageSource("GEOMETRY", null),
                new IrisTransformKeyEncoder.StageSource("VERTEX", "vert")), environment(false));
        ArtifactKey empty = encode(parameters, List.of(
                new IrisTransformKeyEncoder.StageSource("FRAGMENT", "frag"),
                new IrisTransformKeyEncoder.StageSource("GEOMETRY", ""),
                new IrisTransformKeyEncoder.StageSource("VERTEX", "vert")), environment(false));
        require(absent != null && empty != null, "null or empty stage failed to key");
        require(!absent.equals(empty), "null stage aliased an empty stage");
    }

    private static void environmentAndContextInputsInvalidate() throws Exception {
        ClassLoader loader = Iris1114Fixtures.loader();
        Object base = sodium(loader, "normals", "normals", false);
        List<IrisTransformKeyEncoder.StageSource> sources = sources("A");
        IrisTransformKeyEncoder.Environment environment = environment(false);
        requireDifferent(encode(base, sources, environment), encode(base, sources("B"), environment), "source");
        requireDifferent(encode(base, sources, environment), encode(base, sources, environment(true)), "debug print mode");
        Object remapped = sodium(loader, "normals", "different_sampler", false);
        requireDifferent(encode(base, sources, environment), encode(remapped, sources, environment), "texture mapping");
        Object shadow = sodium(loader, "normals", "normals", true);
        requireDifferent(encode(base, sources, environment), encode(shadow, sources, environment), "shadow context");
        Object alpha = Iris1114Fixtures.newSodiumParameters(
                loader,
                Iris1114Fixtures.textureMap(loader, "normals", "normals"),
                Iris1114Fixtures.alpha(loader, "LESS", 0.5f),
                false);
        requireDifferent(encode(base, sources, environment), encode(alpha, sources, environment), "alpha test");
    }

    private static void mutableTransformerScratchIsExcluded() throws Exception {
        ClassLoader loader = Iris1114Fixtures.loader();
        Object parameters = sodium(loader, "normals", "normals", false);
        ArtifactKey before = encode(parameters, sources("A"), environment(false));
        Class<?> shaderType = loader.loadClass("net.irisshaders.iris.pipeline.transform.PatchShaderType");
        Field type = parameters.getClass().getField("type");
        Field name = parameters.getClass().getField("name");
        type.set(parameters, Enum.valueOf(asEnum(shaderType), "FRAGMENT"));
        name.set(parameters, "mutated-by-transformer");
        ArtifactKey after = encode(parameters, sources("A"), environment(false));
        require(before.equals(after), "Iris transformer scratch polluted the key");
    }

    private static void tamperedParametersFailClosed() throws Exception {
        byte[] original = Iris1114Fixtures.resource(
                "net/irisshaders/iris/pipeline/transform/parameter/Parameters.class");
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "textureOverrides", "Ljava/util/Set;", null, null));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        ClassLoader loader = Iris1114Fixtures.loaderWithTamperedParameters(writer.toByteArray());
        Object parameters = sodium(loader, "normals", "normals", false);
        require(!IrisTransformKeyEncoder.supportedStructure(parameters), "extra field was accepted");
        require(encode(parameters, sources("A"), environment(false)) == null,
                "tampered Parameters produced a key");
    }

    private static void unknownParameterImplementationFailsClosed() {
        ArtifactKey key = IrisTransformKeyEncoder.encode(
                "graphics", "name", sources("A"), new Object(), environment(false));
        require(key == null, "unknown parameter implementation produced a partial key");
    }

    private static Object sodium(
            final ClassLoader loader, final String sampler, final String binding, final boolean shadow)
            throws ReflectiveOperationException {
        return Iris1114Fixtures.newSodiumParameters(
                loader,
                Iris1114Fixtures.textureMap(loader, sampler, binding),
                Iris1114Fixtures.alpha(loader, "GREATER", 0.1f),
                shadow);
    }

    private static Object orderedMap(final ClassLoader loader, final String first, final String second)
            throws ReflectiveOperationException {
        Class<?> mapClass = Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap");
        Object map = mapClass.getConstructor().newInstance();
        mapClass.getMethod("put", Object.class, Object.class)
                .invoke(map, Iris1114Fixtures.tri(loader, first), first);
        mapClass.getMethod("put", Object.class, Object.class)
                .invoke(map, Iris1114Fixtures.tri(loader, second), second);
        return map;
    }

    private static ArtifactKey encode(
            final Object parameters,
            final List<IrisTransformKeyEncoder.StageSource> sources,
            final IrisTransformKeyEncoder.Environment environment) {
        return IrisTransformKeyEncoder.encode("graphics", "gbuffers_terrain", sources, parameters, environment);
    }

    private static List<IrisTransformKeyEncoder.StageSource> sources(final String marker) {
        return List.of(
                new IrisTransformKeyEncoder.StageSource("FRAGMENT", "#version 330\n//fragment"),
                new IrisTransformKeyEncoder.StageSource("GEOMETRY", null),
                new IrisTransformKeyEncoder.StageSource("TESS_CONTROL", null),
                new IrisTransformKeyEncoder.StageSource("TESS_EVAL", null),
                new IrisTransformKeyEncoder.StageSource("VERTEX", "#version 330\n//" + marker));
    }

    private static IrisTransformKeyEncoder.Environment environment(final boolean debug) {
        return new IrisTransformKeyEncoder.Environment(
                "iris-transform-patcher-1.11.4-mc26.2-r1",
                "official-jar-and-class-fingerprint",
                "1.11.4+mc26.2",
                "26.2",
                debug,
                false);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> asEnum(final Class<?> type) {
        return (Class)type;
    }

    private static void requireDifferent(final ArtifactKey left, final ArtifactKey right, final String input) {
        require(left != null && right != null, input + " failed to build a key");
        require(!left.equals(right), input + " change did not invalidate the key");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
