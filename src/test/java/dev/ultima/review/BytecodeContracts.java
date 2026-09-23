package dev.ultima.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** ASM reads of compiled Ultima classes. These replace source-text greps. */
public final class BytecodeContracts {
    private BytecodeContracts() {
    }

    public static ClassNode loadFile(final Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    public static ClassNode load(final String binaryName) throws IOException {
        String resource = binaryName.replace('.', '/') + ".class";
        try (InputStream input = BytecodeContracts.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("compiled class missing: " + binaryName);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    public static boolean invokesOwner(final ClassNode node, final String owner) {
        return invokes(node, owner, null);
    }

    public static boolean invokes(final ClassNode node, final String owner, final String name) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && (name == null || name.equals(call.name))) {
                    return true;
                }
                if (insn instanceof InvokeDynamicInsnNode indy && handleReferences(indy.bsmArgs, owner, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean readsField(final ClassNode node, final String owner, final String name) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && owner.equals(field.owner) && name.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean mentions(final ClassNode node, final String token) {
        if (node.name != null && node.name.contains(token)) {
            return true;
        }
        for (var field : node.fields) {
            if (contains(field.desc, token) || contains(field.signature, token) || contains(field.name, token)) {
                return true;
            }
        }
        for (MethodNode method : node.methods) {
            if (contains(method.desc, token) || contains(method.signature, token) || contains(method.name, token)) {
                return true;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.contains(token)) {
                    return true;
                }
                if (insn instanceof MethodInsnNode call
                        && (contains(call.owner, token) || contains(call.name, token) || contains(call.desc, token))) {
                    return true;
                }
                if (insn instanceof FieldInsnNode field
                        && (contains(field.owner, token) || contains(field.name, token) || contains(field.desc, token))) {
                    return true;
                }
                if (insn instanceof TypeInsnNode type && contains(type.desc, token)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void requireInjectRequire(
            final ClassNode node, final String targetFragment, final int expectedRequire) {
        boolean found = false;
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotation.desc)) {
                    continue;
                }
                if (!annotationText(annotation).contains(targetFragment)) {
                    continue;
                }
                found = true;
                Object require = annotationValue(annotation, "require");
                int actual = require instanceof Integer integer ? integer : Integer.MIN_VALUE;
                if (actual != expectedRequire) {
                    throw new AssertionError(node.name + "." + method.name + " target " + targetFragment
                            + " require: expected " + expectedRequire + ", got " + require);
                }
            }
        }
        if (!found) {
            throw new AssertionError(node.name + " has no @Inject targeting " + targetFragment);
        }
    }

    private static boolean handleReferences(final Object[] args, final String owner, final String name) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof Handle handle
                    && owner.equals(handle.getOwner())
                    && (name == null || name.equals(handle.getName()))) {
                return true;
            }
            if (arg instanceof Object[] nested && handleReferences(nested, owner, name)) {
                return true;
            }
        }
        return false;
    }

    private static List<AnnotationNode> annotations(final MethodNode method) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            all.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            all.addAll(method.invisibleAnnotations);
        }
        return all;
    }

    private static String annotationText(final AnnotationNode annotation) {
        StringBuilder text = new StringBuilder();
        appendAnnotation(text, annotation);
        return text.toString();
    }

    private static void appendAnnotation(final StringBuilder text, final AnnotationNode annotation) {
        if (annotation.values == null) {
            return;
        }
        for (int i = 1; i < annotation.values.size(); i += 2) {
            appendValue(text, annotation.values.get(i));
        }
    }

    private static void appendValue(final StringBuilder text, final Object value) {
        if (value instanceof String string) {
            text.append(string).append('\n');
        } else if (value instanceof AnnotationNode nested) {
            appendAnnotation(text, nested);
        } else if (value instanceof List<?> list) {
            for (Object entry : list) {
                appendValue(text, entry);
            }
        } else if (value != null) {
            text.append(value).append('\n');
        }
    }

    private static Object annotationValue(final AnnotationNode annotation, final String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static boolean contains(final String value, final String token) {
        return value != null && value.contains(token);
    }
}
