package dev.ultima.review;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Bytecode-level Mixin wiring checks; these inspect compiled annotations and vanilla targets. */
public final class MixinBytecodeChecks {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";

    private MixinBytecodeChecks() {
    }

    public static void main(final String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        List<ClassNode> mixins = compiledMixins();
        if (mixins.isEmpty()) {
            throw new AssertionError("No compiled Mixin classes found");
        }

        Map<String, String> generatedMethods = new HashMap<>();
        for (ClassNode mixin : mixins) {
            AnnotationNode mixinAnnotation = annotation(mixin.invisibleAnnotations, MIXIN);
            if (mixinAnnotation == null) {
                mixinAnnotation = annotation(mixin.visibleAnnotations, MIXIN);
            }
            if (mixinAnnotation == null) {
                continue;
            }
            List<String> targets = mixinTargets(mixinAnnotation);
            for (MethodNode handler : mixin.methods) {
                AnnotationNode inject = annotation(handler.invisibleAnnotations, INJECT);
                if (inject == null) {
                    inject = annotation(handler.visibleAnnotations, INJECT);
                }
                if (inject != null) {
                    checkConstructorHeadIsStatic(mixin, handler, inject);
                    checkSelectedMethodsExist(mixin, handler, targets, inject, true);
                    checkAtTargetExists(mixin, targets, inject);
                }

                AnnotationNode redirect = annotation(handler.invisibleAnnotations, REDIRECT);
                if (redirect == null) {
                    redirect = annotation(handler.visibleAnnotations, REDIRECT);
                }
                if (redirect != null) {
                    checkRedirectContract(mixin, redirect);
                    checkSelectedMethodsExist(mixin, handler, targets, redirect, false);
                    checkAtTargetExists(mixin, targets, redirect);
                }

                if (annotation(handler.invisibleAnnotations, ACCESSOR) != null
                        || annotation(handler.visibleAnnotations, ACCESSOR) != null) {
                    for (String target : targets) {
                        String key = target + '#' + handler.name + handler.desc;
                        String previous = generatedMethods.putIfAbsent(key, mixin.name);
                        if (previous != null) {
                            throw new AssertionError("Duplicate generated Mixin method " + key
                                    + " in " + previous + " and " + mixin.name);
                        }
                    }
                }
            }
        }

        checkPriority("dev/ultima/mixin/fsr_upscaling/GameRendererMixin", 1100);
        checkPriority("dev/ultima/mixin/temporal/GameRendererMixin", 900);
        checkFsrChainableHooks();
        checkConfigContracts();
        checkVanillaSynchronization();
        checkPinnedKillerContracts();
        System.out.println("Compiled Mixin bytecode and current vanilla target contracts passed.");
    }

    private static void checkPinnedKillerContracts() throws IOException {
        ClassNode parameters = fixture("upstream/iris-1.11.4/net/irisshaders/iris/pipeline/transform/parameter/Parameters.class");
        List<String> fields = parameters.fields.stream().map(field -> field.name).sorted().toList();
        if (!fields.equals(List.of("name", "patch", "textureMap", "type"))) {
            throw new AssertionError("Iris Parameters field set changed: " + fields);
        }
        ClassNode patcher = fixture("upstream/iris-1.11.4/net/irisshaders/iris/pipeline/transform/TransformPatcher.class");
        requireMethod(patcher, "transformInternal",
                "(Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;");
        requireMethod(patcher, "transform",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;");
        requireMethod(patcher, "transformCompute",
                "(Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;");
        requireCacheBeforeInternal(patcher, "transform");
        requireCacheBeforeInternal(patcher, "transformCompute");

        ClassNode irisMixin = readResource("dev/ultima/mixin/iris_shader_frontend_artifact_cache/TransformPatcherMixin.class");
        if (irisMixin == null) {
            throw new AssertionError("compiled TransformPatcherMixin is missing");
        }
        boolean wrapsInternal = false;
        for (MethodNode method : irisMixin.methods) {
            AnnotationNode wrap = annotation(method.invisibleAnnotations,
                    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
            if (wrap == null) {
                wrap = annotation(method.visibleAnnotations,
                        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
            }
            if (wrap == null) {
                continue;
            }
            AnnotationNode at = firstAnnotation(value(wrap, "at"));
            String target = at == null ? "" : String.valueOf(value(at, "target"));
            if (target.contains("transformInternal")) {
                wrapsInternal = true;
            }
            if (Boolean.TRUE.equals(value(wrap, "cancellable"))) {
                throw new AssertionError("Iris transform wrapper must not cancel the owner method");
            }
        }
        if (!wrapsInternal) {
            throw new AssertionError("Iris mixin must wrap transformInternal so Iris L1 stays in front");
        }

        ClassNode sodium = fixture(
                "upstream/sodium-0.9.2/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.class");
        MethodNode submit = sodium.methods.stream()
                .filter(method -> method.name.equals("submitDeferredSectionTasks"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sodium submitDeferredSectionTasks is missing"));
        int budget = instructionIndex(submit, "hasBudgetRemaining");
        int upload = instructionIndex(submit, "isAvailable");
        int dequeue = instructionIndex(submit, "dequeueNextSectionPos");
        if (budget < 0 || upload < 0 || dequeue < 0 || !(budget < dequeue && upload < dequeue)) {
            throw new AssertionError("Sodium deferred submit no longer checks budget before dequeue");
        }
        ClassNode brokerMixin = readResource(
                "dev/ultima/mixin/cross_pipeline_admission_broker/RenderSectionManagerMixin.class");
        if (brokerMixin == null) {
            throw new AssertionError("compiled RenderSectionManagerMixin is missing");
        }
        for (MethodNode method : brokerMixin.methods) {
            AnnotationNode inject = annotation(method.invisibleAnnotations, INJECT);
            if (inject == null) {
                inject = annotation(method.visibleAnnotations, INJECT);
            }
            if (inject == null) {
                continue;
            }
            if (strings(value(inject, "method")).stream().anyMatch(selector -> selector.startsWith("submitDeferredSectionTasks"))
                    && Boolean.TRUE.equals(value(inject, "cancellable"))) {
                throw new AssertionError("broker must not cancel submitDeferredSectionTasks");
            }
        }
    }

    private static void requireCacheBeforeInternal(final ClassNode patcher, final String methodName) {
        MethodNode method = patcher.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + methodName));
        int cache = instructionIndex(method, "containsKey");
        int internal = instructionIndex(method, "transformInternal");
        if (cache < 0 || internal < 0 || cache > internal) {
            throw new AssertionError(methodName + " does not consult the Iris L1 cache before transformInternal");
        }
    }

    private static int instructionIndex(final MethodNode method, final String token) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && (call.name.equals(token) || call.owner.contains(token))) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static void requireMethod(final ClassNode node, final String name, final String desc) {
        boolean found = node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
        if (!found) {
            throw new AssertionError(node.name + " is missing " + name + desc);
        }
    }

    private static ClassNode fixture(final String resource) throws IOException {
        ClassNode node = readResource(resource);
        if (node == null) {
            throw new AssertionError("pinned fixture missing: " + resource);
        }
        return node;
    }

    private static void checkConstructorHeadIsStatic(
            final ClassNode mixin, final MethodNode handler, final AnnotationNode inject) {
        List<String> selectors = strings(value(inject, "method"));
        AnnotationNode at = firstAnnotation(value(inject, "at"));
        if (!selectors.stream().anyMatch(selector -> selector.startsWith("<init>"))
                || at == null
                || !"HEAD".equals(value(at, "value"))) {
            return;
        }
        if ((handler.access & Opcodes.ACC_STATIC) == 0) {
            throw new AssertionError(mixin.name + '.' + handler.name
                    + " injects at constructor HEAD but is not static");
        }
    }

    private static void checkRedirectContract(final ClassNode mixin, final AnnotationNode redirect) {
        List<String> selectors = strings(value(redirect, "method"));
        if (selectors.size() > 1) {
            throw new AssertionError(mixin.name
                    + " redirects several target methods with one aggregate require contract: " + selectors);
        }
    }

    private static void checkAtTargetExists(
            final ClassNode mixin, final List<String> targets, final AnnotationNode injector) throws IOException {
        AnnotationNode at = firstAnnotation(value(injector, "at"));
        String instructionTarget = at == null ? null : (String)value(at, "target");
        if (instructionTarget == null || instructionTarget.isBlank()) {
            return;
        }
        for (String target : targets) {
            ClassNode targetNode = readResource(target + ".class");
            if (targetNode == null) {
                continue;
            }
            for (String selector : strings(value(injector, "method"))) {
                if (countInstructionMatches(targetNode, selector, instructionTarget) == 0) {
                    throw new AssertionError(mixin.name + " injector target " + instructionTarget
                            + " is absent from " + target + '.' + selector);
                }
            }
        }
    }

    private static void checkSelectedMethodsExist(
            final ClassNode mixin,
            final MethodNode handler,
            final List<String> targets,
            final AnnotationNode injector,
            final boolean callbackInjector) throws IOException {
        for (String target : targets) {
            ClassNode targetNode = readResource(target + ".class");
            if (targetNode == null) {
                continue;
            }
            for (String selector : strings(value(injector, "method"))) {
                int paren = selector.indexOf('(');
                String name = paren < 0 ? selector : selector.substring(0, paren);
                String desc = paren < 0 ? null : selector.substring(paren);
                boolean exists = targetNode.methods.stream()
                        .anyMatch(method -> method.name.equals(name) && (desc == null || method.desc.equals(desc)));
                if (!exists) {
                    throw new AssertionError(mixin.name + " selects missing target method "
                            + target + '.' + selector);
                }
                if (callbackInjector) {
                    for (MethodNode method : targetNode.methods) {
                        if (!method.name.equals(name) || desc != null && !method.desc.equals(desc)) {
                            continue;
                        }
                        boolean returnsVoid = Type.getReturnType(method.desc).getSort() == Type.VOID;
                        Type[] handlerArguments = Type.getArgumentTypes(handler.desc);
                        String expected = returnsVoid
                                ? "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
                                : "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
                        boolean hasExpectedCallback = java.util.Arrays.stream(handlerArguments)
                                .anyMatch(argument -> expected.equals(argument.getInternalName()));
                        if (!hasExpectedCallback) {
                            throw new AssertionError(mixin.name + '.' + handler.name
                                    + " for target " + target + '.' + method.name + method.desc
                                    + " does not declare " + expected);
                        }
                    }
                }
            }
        }
    }

    private static int countInstructionMatches(
            final ClassNode target, final String selector, final String instructionTarget) {
        int paren = selector.indexOf('(');
        String selectedName = paren < 0 ? selector : selector.substring(0, paren);
        String selectedDesc = paren < 0 ? null : selector.substring(paren);
        int matches = 0;
        for (MethodNode method : target.methods) {
            if (!method.name.equals(selectedName) || selectedDesc != null && !method.desc.equals(selectedDesc)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    String encoded = 'L' + call.owner + ';' + call.name + call.desc;
                    if (encoded.equals(instructionTarget)) {
                        matches++;
                    }
                } else if (instruction instanceof FieldInsnNode field) {
                    String encoded = 'L' + field.owner + ';' + field.name + ':' + field.desc;
                    if (encoded.equals(instructionTarget)) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }

    private static void checkFsrChainableHooks() throws IOException {
        ClassNode mixin = readResource("dev/ultima/mixin/fsr_upscaling/GameRendererMixin.class");
        if (mixin == null) {
            throw new AssertionError("compiled FSR GameRendererMixin is missing");
        }
        int fieldWraps = 0;
        int screenshotWraps = 0;
        for (MethodNode method : mixin.methods) {
            if (annotation(method.invisibleAnnotations, REDIRECT) != null
                    || annotation(method.visibleAnnotations, REDIRECT) != null) {
                throw new AssertionError("FSR GameRendererMixin still uses @Redirect on " + method.name);
            }
            AnnotationNode wrap = annotation(method.invisibleAnnotations, WRAP);
            if (wrap == null) {
                wrap = annotation(method.visibleAnnotations, WRAP);
            }
            if (wrap == null) {
                continue;
            }
            AnnotationNode at = firstAnnotation(value(wrap, "at"));
            String target = at == null ? "" : String.valueOf(value(at, "target"));
            if (target.contains("mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")) {
                fieldWraps++;
            }
            if (target.contains("tryTakeScreenshotIfNeeded()V")) {
                screenshotWraps++;
            }
        }
        if (fieldWraps != 2) {
            throw new AssertionError("FSR must wrap both mainRenderTarget reads, found " + fieldWraps);
        }
        if (screenshotWraps != 1) {
            throw new AssertionError("FSR must wrap the world-icon screenshot call, found " + screenshotWraps);
        }
    }

    private static void checkPriority(final String resourceName, final int expected) throws IOException {
        ClassNode node = readResource(resourceName + ".class");
        AnnotationNode mixin = annotation(node == null ? null : node.invisibleAnnotations, MIXIN);
        if (mixin == null && node != null) {
            mixin = annotation(node.visibleAnnotations, MIXIN);
        }
        Object priority = mixin == null ? null : value(mixin, "priority");
        int actual = priority instanceof Integer integer ? integer : 1000;
        if (actual != expected) {
            throw new AssertionError(resourceName + " priority: expected " + expected + ", got " + actual);
        }
    }

    private static void checkConfigContracts() throws IOException {
        for (String resource : List.of("ultima.mixins.json", "ultima.client.mixins.json")) {
            String json;
            try (InputStream input = MixinBytecodeChecks.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new AssertionError("Missing " + resource);
                }
                json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!json.contains("\"compatibilityLevel\": \"JAVA_25\"")) {
                throw new AssertionError(resource + " does not declare JAVA_25");
            }
        }
    }

    private static void checkVanillaSynchronization() throws ClassNotFoundException, NoSuchMethodException {
        Class<?> queue = Class.forName("net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue");
        if (!Modifier.isSynchronized(queue.getDeclaredMethod(
                        "poll", Class.forName("net.minecraft.world.phys.Vec3")).getModifiers())) {
            throw new AssertionError("SectionTaskDynamicQueue.poll is no longer synchronized");
        }
    }

    private static List<ClassNode> compiledMixins() throws IOException {
        Path mainRoot = Path.of("build/classes/java/main/dev/ultima/mixin");
        Path clientRoot = Path.of("build/classes/java/client/dev/ultima/mixin");
        if (!Files.isDirectory(mainRoot)) {
            throw new AssertionError("compiled common mixins are missing: " + mainRoot);
        }
        if (!Files.isDirectory(clientRoot)) {
            throw new AssertionError("compiled client mixins are missing: " + clientRoot);
        }
        List<ClassNode> result = new ArrayList<>();
        for (Path root : List.of(mainRoot, clientRoot)) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    try (InputStream input = Files.newInputStream(file)) {
                        ClassNode node = new ClassNode();
                        new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        result.add(node);
                    }
                }
            }
        }
        requireConfiguredMixinClasses("ultima.mixins.json", "mixins");
        requireConfiguredMixinClasses("ultima.client.mixins.json", "client");
        return result;
    }

    private static void requireConfiguredMixinClasses(final String resource, final String arrayName) throws IOException {
        String json;
        try (InputStream input = MixinBytecodeChecks.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing " + resource);
            }
            json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int arrayAt = json.indexOf("\"" + arrayName + "\"");
        if (arrayAt < 0) {
            throw new AssertionError(resource + " has no " + arrayName + " array");
        }
        int start = json.indexOf('[', arrayAt);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) {
            throw new AssertionError(resource + " " + arrayName + " array is malformed");
        }
        String body = json.substring(start + 1, end);
        for (String raw : body.split(",")) {
            String name = raw.replace("\"", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            String binary = "dev.ultima.mixin." + name;
            if (MixinBytecodeChecks.class.getClassLoader().getResource(binary.replace('.', '/') + ".class") == null) {
                throw new AssertionError("configured mixin class is not compiled: " + binary);
            }
        }
    }

    private static ClassNode readResource(final String resource) throws IOException {
        try (InputStream input = MixinBytecodeChecks.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static List<String> mixinTargets(final AnnotationNode mixin) {
        List<String> targets = new ArrayList<>();
        Object classes = value(mixin, "value");
        if (classes instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Type type) {
                    targets.add(type.getInternalName());
                }
            }
        }
        for (String target : strings(value(mixin, "targets"))) {
            targets.add(target.replace('.', '/'));
        }
        return targets;
    }

    private static List<String> strings(final Object value) {
        if (value instanceof String string) {
            return List.of(string);
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static AnnotationNode firstAnnotation(final Object value) {
        if (value instanceof AnnotationNode annotation) {
            return annotation;
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof AnnotationNode annotation) {
            return annotation;
        }
        return null;
    }

    private static AnnotationNode annotation(final List<AnnotationNode> annotations, final String desc) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object value(final AnnotationNode annotation, final String key) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }
}
