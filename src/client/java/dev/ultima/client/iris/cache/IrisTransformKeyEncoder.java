package dev.ultima.client.iris.cache;

import dev.ultima.cache.iris.ArtifactKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative encoder for the exact Iris 1.11.4 transformation parameter graph. */
final class IrisTransformKeyEncoder {
    static final int KEY_SCHEMA = 1;
    private static final int MAX_DEPTH = 12;
    private static final String PARAMETER_PREFIX = "net.irisshaders.iris.pipeline.transform.parameter.";
    private static final Set<String> PARAMETER_CLASSES = Set.of(
            PARAMETER_PREFIX + "ComputeParameters",
            PARAMETER_PREFIX + "DHParameters",
            PARAMETER_PREFIX + "SodiumParameters",
            PARAMETER_PREFIX + "TextureStageParameters",
            PARAMETER_PREFIX + "VanillaParameters");
    private static final Set<String> RECORD_CLASSES = Set.of(
            "net.irisshaders.iris.helpers.Tri",
            "net.irisshaders.iris.gl.blending.AlphaTest");
    private static final Map<String, Set<String>> DECLARED_FIELD_SCHEMA = fieldSchema();

    private static final ClassValue<ParameterPlan> PARAMETER_PLANS = new ClassValue<>() {
        @Override
        protected ParameterPlan computeValue(final Class<?> type) {
            return buildPlan(type);
        }
    };

    private IrisTransformKeyEncoder() {
    }

    static @org.jspecify.annotations.Nullable ArtifactKey encode(
            final String transformKind,
            final String name,
            final List<StageSource> sources,
            final Object parameters,
            final Environment environment) {
        if (parameters == null || !PARAMETER_CLASSES.contains(parameters.getClass().getName())) {
            return null;
        }
        ParameterPlan plan = PARAMETER_PLANS.get(parameters.getClass());
        if (!plan.supported()) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest))) {
                output.writeInt(KEY_SCHEMA);
                writeString(output, "ultima-iris-frontend-artifact");
                writeString(output, environment.adapterId());
                writeString(output, environment.adapterFingerprint());
                writeString(output, environment.irisVersion());
                writeString(output, environment.minecraftVersion());
                output.writeBoolean(environment.debugOptions());
                output.writeBoolean(environment.zZeroToOne());
                writeString(output, transformKind);
                writeNullableString(output, name);
                output.writeInt(sources.size());
                for (StageSource source : sources) {
                    writeString(output, source.stage());
                    writeNullableString(output, source.source());
                }
                writeString(output, parameters.getClass().getName());
                IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
                for (Field field : plan.fields()) {
                    writeString(output, field.getDeclaringClass().getName() + "#" + field.getName());
                    encodeValue(output, field.get(parameters), visiting, 0);
                }
            }
            return new ArtifactKey(digest.digest());
        } catch (NoSuchAlgorithmException | IOException | IllegalAccessException | RuntimeException exception) {
            return null;
        }
    }

    private static ParameterPlan buildPlan(final Class<?> concrete) {
        if (!PARAMETER_CLASSES.contains(concrete.getName())) {
            return ParameterPlan.unsupported();
        }
        List<Field> encoded = new ArrayList<>();
        Class<?> current = concrete;
        while (current != null && current.getName().startsWith(PARAMETER_PREFIX)) {
            Set<String> expected = DECLARED_FIELD_SCHEMA.get(current.getName());
            if (expected == null) {
                return ParameterPlan.unsupported();
            }
            List<Field> actual = Arrays.stream(current.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                    .toList();
            Set<String> actualNames = actual.stream().map(Field::getName).collect(java.util.stream.Collectors.toSet());
            if (!actualNames.equals(expected)) {
                return ParameterPlan.unsupported();
            }
            for (Field field : actual) {
                if (current.getName().equals(PARAMETER_PREFIX + "Parameters")
                        && (field.getName().equals("type") || field.getName().equals("name"))) {
                    continue;
                }
                if (!field.trySetAccessible()) {
                    return ParameterPlan.unsupported();
                }
                encoded.add(field);
            }
            current = current.getSuperclass();
        }
        if (current == null || !current.equals(Object.class)) {
            return ParameterPlan.unsupported();
        }
        encoded.sort(Comparator.comparing(field -> field.getDeclaringClass().getName() + "#" + field.getName()));
        return new ParameterPlan(true, List.copyOf(encoded));
    }

    private static void encodeValue(
            final DataOutputStream output,
            final Object value,
            final IdentityHashMap<Object, Boolean> visiting,
            final int depth) throws IOException, IllegalAccessException {
        if (depth > MAX_DEPTH) {
            throw new IOException("parameter graph too deep");
        }
        if (value == null) {
            output.writeByte(0);
            return;
        }
        if (value instanceof String string) {
            output.writeByte(1);
            writeString(output, string);
            return;
        }
        if (value instanceof Boolean bool) {
            output.writeByte(2);
            output.writeBoolean(bool);
            return;
        }
        if (value instanceof Byte number) {
            output.writeByte(3);
            output.writeByte(number);
            return;
        }
        if (value instanceof Short number) {
            output.writeByte(4);
            output.writeShort(number);
            return;
        }
        if (value instanceof Integer number) {
            output.writeByte(5);
            output.writeInt(number);
            return;
        }
        if (value instanceof Long number) {
            output.writeByte(6);
            output.writeLong(number);
            return;
        }
        if (value instanceof Float number) {
            output.writeByte(7);
            output.writeInt(Float.floatToRawIntBits(number));
            return;
        }
        if (value instanceof Double number) {
            output.writeByte(8);
            output.writeLong(Double.doubleToRawLongBits(number));
            return;
        }
        if (value instanceof Character character) {
            output.writeByte(9);
            output.writeChar(character);
            return;
        }
        if (value instanceof Enum<?> enumeration) {
            output.writeByte(10);
            writeString(output, enumeration.getDeclaringClass().getName());
            writeString(output, enumeration.name());
            return;
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IOException("cyclic parameter graph");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                output.writeByte(11);
                List<byte[]> entries = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    entries.add(encodedPair(entry.getKey(), entry.getValue(), visiting, depth + 1));
                }
                entries.sort(IrisTransformKeyEncoder::compareUnsigned);
                output.writeInt(entries.size());
                for (byte[] entry : entries) {
                    output.writeInt(entry.length);
                    output.write(entry);
                }
                return;
            }
            if (value instanceof Set<?> set) {
                output.writeByte(12);
                List<byte[]> members = new ArrayList<>(set.size());
                for (Object member : set) {
                    members.add(encodedValue(member, visiting, depth + 1));
                }
                members.sort(IrisTransformKeyEncoder::compareUnsigned);
                output.writeInt(members.size());
                for (byte[] member : members) {
                    output.writeInt(member.length);
                    output.write(member);
                }
                return;
            }

            Class<?> type = value.getClass();
            if (type.isRecord() && RECORD_CLASSES.contains(type.getName())) {
                output.writeByte(13);
                writeString(output, type.getName());
                RecordComponent[] components = type.getRecordComponents();
                output.writeInt(components.length);
                for (RecordComponent component : components) {
                    writeString(output, component.getName());
                    try {
                        encodeValue(output, component.getAccessor().invoke(value), visiting, depth + 1);
                    } catch (ReflectiveOperationException exception) {
                        throw new IOException("record access failed", exception);
                    }
                }
                return;
            }
            if (type.getName().equals("net.irisshaders.iris.gl.state.ShaderAttributeInputs")) {
                output.writeByte(14);
                writeString(output, type.getName());
                List<Field> fields = primitiveFields(type, Set.of(
                        "ie", "color", "tex", "overlay", "light", "normal", "newLines", "glint", "text",
                        "entityComponents"));
                for (Field field : fields) {
                    writeString(output, field.getName());
                    encodeValue(output, field.get(value), visiting, depth + 1);
                }
                return;
            }
            throw new IOException("unsupported parameter value " + type.getName());
        } finally {
            visiting.remove(value);
        }
    }

    private static List<Field> primitiveFields(final Class<?> type, final Set<String> expected) throws IOException {
        List<Field> fields = Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .sorted(Comparator.comparing(Field::getName))
                .toList();
        Set<String> actual = fields.stream().map(Field::getName).collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(expected)) {
            throw new IOException("field schema changed for " + type.getName());
        }
        for (Field field : fields) {
            if (!field.getType().isPrimitive() || !field.trySetAccessible()) {
                throw new IOException("unsupported field in " + type.getName());
            }
        }
        return fields;
    }

    private static byte[] encodedPair(
            final Object key,
            final Object value,
            final IdentityHashMap<Object, Boolean> visiting,
            final int depth) throws IOException, IllegalAccessException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encodeValue(output, key, visiting, depth);
            encodeValue(output, value, visiting, depth);
        }
        return bytes.toByteArray();
    }

    private static byte[] encodedValue(
            final Object value,
            final IdentityHashMap<Object, Boolean> visiting,
            final int depth) throws IOException, IllegalAccessException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encodeValue(output, value, visiting, depth);
        }
        return bytes.toByteArray();
    }

    private static int compareUnsigned(final byte[] left, final byte[] right) {
        return Arrays.compareUnsigned(left, right);
    }

    private static void writeNullableString(final DataOutputStream output, final String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static void writeString(final DataOutputStream output, final String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static Map<String, Set<String>> fieldSchema() {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        schema.put(PARAMETER_PREFIX + "Parameters", Set.of("patch", "textureMap", "textureOverrides", "type", "name"));
        schema.put(PARAMETER_PREFIX + "GeometryInfoParameters", Set.of("hasGeometry", "hasTesselation"));
        schema.put(PARAMETER_PREFIX + "ComputeParameters", Set.of());
        schema.put(PARAMETER_PREFIX + "DHParameters", Set.of());
        schema.put(PARAMETER_PREFIX + "SodiumParameters", Set.of("alpha", "shadow"));
        schema.put(PARAMETER_PREFIX + "TextureStageParameters", Set.of("stage"));
        schema.put(PARAMETER_PREFIX + "VanillaParameters", Set.of("alpha", "inputs", "hasChunkOffset", "isLines", "isClouds"));
        return Map.copyOf(schema);
    }

    record StageSource(String stage, @org.jspecify.annotations.Nullable String source) {
    }

    record Environment(
            String adapterId,
            String adapterFingerprint,
            String irisVersion,
            String minecraftVersion,
            boolean debugOptions,
            boolean zZeroToOne) {
    }

    private record ParameterPlan(boolean supported, List<Field> fields) {
        static ParameterPlan unsupported() {
            return new ParameterPlan(false, List.of());
        }
    }
}
