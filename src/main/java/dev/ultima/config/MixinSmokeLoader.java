package dev.ultima.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/** Force-loads every common Mixin target during the opt-in headless smoke run. */
public final class MixinSmokeLoader {
    public static final String PROPERTY = "ultima.mixinSmoke";
    private static final String CONFIG = "ultima.mixins.json";
    private static final String MIXIN_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/Mixin;";

    private MixinSmokeLoader() {
    }

    public static int forceLoadCommonTargets() {
        if (!Boolean.getBoolean(PROPERTY)) {
            return 0;
        }
        try {
            ClassLoader loader = MixinSmokeLoader.class.getClassLoader();
            Set<String> targets = new LinkedHashSet<>();
            try (InputStream input = loader.getResourceAsStream(CONFIG)) {
                if (input == null) {
                    throw new IllegalStateException("Missing " + CONFIG);
                }
                JsonObject config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String mixinPackage = config.get("package").getAsString();
                JsonArray mixins = config.getAsJsonArray("mixins");
                for (var entry : mixins) {
                    collectTargets(loader, mixinPackage + '.' + entry.getAsString(), targets);
                }
            }
            for (String target : targets) {
                Class.forName(target.replace('/', '.'), false, loader);
            }
            return targets.size();
        } catch (Throwable error) {
            throw new IllegalStateException("Ultima common Mixin smoke failed", error);
        }
    }

    private static void collectTargets(
            final ClassLoader loader, final String mixinClass, final Set<String> targets) throws Exception {
        String resource = mixinClass.replace('.', '/') + ".class";
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing compiled Mixin " + mixinClass);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            AnnotationNode mixin = find(node.invisibleAnnotations);
            if (mixin == null) {
                mixin = find(node.visibleAnnotations);
            }
            if (mixin == null) {
                throw new IllegalStateException(mixinClass + " has no @Mixin annotation");
            }
            Object classTargets = value(mixin, "value");
            if (classTargets instanceof List<?> list) {
                for (Object target : list) {
                    if (target instanceof Type type) {
                        targets.add(type.getInternalName());
                    }
                }
            }
            Object stringTargets = value(mixin, "targets");
            if (stringTargets instanceof List<?> list) {
                for (Object target : list) {
                    if (target instanceof String name) {
                        targets.add(name.replace('.', '/'));
                    }
                }
            }
        }
    }

    private static AnnotationNode find(final List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN_DESCRIPTOR.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static Object value(final AnnotationNode annotation, final String key) {
        if (annotation.values != null) {
            for (int index = 0; index < annotation.values.size(); index += 2) {
                if (key.equals(annotation.values.get(index))) {
                    return annotation.values.get(index + 1);
                }
            }
        }
        return null;
    }
}
