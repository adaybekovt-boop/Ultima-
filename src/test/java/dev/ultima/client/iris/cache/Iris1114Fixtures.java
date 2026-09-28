package dev.ultima.client.iris.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Loads class bytes extracted from the pinned Iris {@code 1.11.4+mc26.2} jar
 * ({@code f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c}).
 * These are not hand-written stand-ins.
 */
final class Iris1114Fixtures {
    static final String PARAMETERS = "net.irisshaders.iris.pipeline.transform.parameter.Parameters";
    static final String SODIUM_PARAMETERS = "net.irisshaders.iris.pipeline.transform.parameter.SodiumParameters";
    static final String RESOURCE_ROOT = "upstream/iris-1.11.4/";

    private Iris1114Fixtures() {
    }

    static ClassLoader loader() {
        return new FixtureLoader(Iris1114Fixtures.class.getClassLoader(), null, null);
    }

    static ClassLoader loaderWithTamperedParameters(final byte[] parametersBytes) {
        return new FixtureLoader(Iris1114Fixtures.class.getClassLoader(), PARAMETERS, parametersBytes);
    }

    static byte[] resource(final String internalName) throws IOException {
        String path = RESOURCE_ROOT + internalName;
        try (InputStream input = Iris1114Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing pinned Iris fixture " + path);
            }
            return input.readAllBytes();
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private final String replacementName;
        private final byte[] replacementBytes;

        FixtureLoader(final ClassLoader parent, final String replacementName, final byte[] replacementBytes) {
            super(parent);
            this.replacementName = replacementName;
            this.replacementBytes = replacementBytes;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.equals(this.replacementName)) {
                return defineClass(name, this.replacementBytes, 0, this.replacementBytes.length);
            }
            String internal = name.replace('.', '/') + ".class";
            byte[] bytes;
            try {
                bytes = resource(internal);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static Object newSodiumParameters(
            final ClassLoader loader,
            final Object textureMap,
            final Object alphaTest,
            final boolean shadow) throws ReflectiveOperationException {
        Class<?> patch = loader.loadClass("net.irisshaders.iris.pipeline.transform.Patch");
        Class<?> alpha = loader.loadClass("net.irisshaders.iris.gl.blending.AlphaTest");
        Class<?> mapType = Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectMap");
        Class<?> type = loader.loadClass(SODIUM_PARAMETERS);
        Object patchValue = Enum.valueOf(asEnum(patch), "SODIUM");
        return type.getConstructor(patch, mapType, alpha, boolean.class)
                .newInstance(patchValue, textureMap, alphaTest, shadow);
    }

    static Object textureMap(final ClassLoader loader, final String sampler, final String binding)
            throws ReflectiveOperationException {
        Class<?> mapClass = Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap");
        Object map = mapClass.getConstructor().newInstance();
        mapClass.getMethod("put", Object.class, Object.class).invoke(map, tri(loader, sampler), binding);
        return map;
    }

    static Object tri(final ClassLoader loader, final String sampler) throws ReflectiveOperationException {
        Class<?> tri = loader.loadClass("net.irisshaders.iris.helpers.Tri");
        Class<?> textureType = loader.loadClass("net.irisshaders.iris.gl.texture.TextureType");
        Class<?> stage = loader.loadClass("net.irisshaders.iris.shaderpack.texture.TextureStage");
        return tri.getConstructor(Object.class, Object.class, Object.class).newInstance(
                sampler,
                Enum.valueOf(asEnum(textureType), "TEXTURE_2D"),
                Enum.valueOf(asEnum(stage), "GBUFFERS_AND_SHADOW"));
    }

    static Object alpha(final ClassLoader loader, final String function, final float reference)
            throws ReflectiveOperationException {
        Class<?> functionType = loader.loadClass("net.irisshaders.iris.gl.blending.AlphaTestFunction");
        Class<?> alpha = loader.loadClass("net.irisshaders.iris.gl.blending.AlphaTest");
        return alpha.getConstructor(functionType, float.class)
                .newInstance(Enum.valueOf(asEnum(functionType), function), reference);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> asEnum(final Class<?> type) {
        return (Class)Objects.requireNonNull(type);
    }
}
