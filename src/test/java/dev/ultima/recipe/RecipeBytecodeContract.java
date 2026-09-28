package dev.ultima.recipe;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Pins the 26.2 {@code matches} bytecode Ultima's allowlist assumes, and the reload order that
 * publishes tags before {@code RecipeManager.finalizeRecipeLoading}.
 *
 * <p>Hashes cover the symbolic instruction listing, not raw class bytes: Loom {@code genSources}
 * rewrites the project's Minecraft jar with line maps and a reordered constant pool, which changes
 * raw {@code Code} bytes without changing a single instruction.
 */
final class RecipeBytecodeContract {
    private static final int LEVEL_SLOT = 2;

    private RecipeBytecodeContract() {
    }

    static void run() throws Exception {
        Map<String, String> typedMatches = Map.of(
                "net/minecraft/world/item/crafting/ShapedRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "6f6be9398d8d2a630043ac5a84713d7fccf357ba4c3d4d8990e7dfc9108eb433",
                "net/minecraft/world/item/crafting/ShapelessRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "990bf238d1cc062dbecf8ff0981a3bdebc9365e3b69b72d22b4f4415dad2fe8b",
                "net/minecraft/world/item/crafting/RepairItemRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "d8ebb802752de4bcce76cf917fe7fa28f76cc39486017a76f4ca601b9ff2fdb9",
                "net/minecraft/world/item/crafting/SingleItemRecipe.class#matches(Lnet/minecraft/world/item/crafting/SingleRecipeInput;Lnet/minecraft/world/level/Level;)Z",
                "20948bffe06a32331d2308908c86901507dcdf7a207b28a9af82d2e06150f9a1",
                "net/minecraft/world/item/crafting/SmithingRecipe.class#matches(Lnet/minecraft/world/item/crafting/SmithingRecipeInput;Lnet/minecraft/world/level/Level;)Z",
                "fbde029bdf571abcd8239f93782c41f3d16821ae539a43f7a5668ec1eae1c160",
                "net/minecraft/world/item/crafting/TransmuteRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "2ce79daebc1ea5c932004154460d49f259e0fef3e54401d0a9c03781f0db6db2",
                "net/minecraft/world/item/crafting/Ingredient.class#test(Lnet/minecraft/world/item/ItemStack;)Z",
                "bb476df8a53b629c5099ff39a8c4bde050303c3f4ecfe4593093669f69210428");
        for (Map.Entry<String, String> entry : typedMatches.entrySet()) {
            MethodNode method = method(entry.getKey());
            String hash = instructionHash(method);
            if (!entry.getValue().equals(hash)) {
                throw new AssertionError(entry.getKey() + " bytecode drifted: " + hash);
            }
            if (readsSlot(method, LEVEL_SLOT)) {
                throw new AssertionError(entry.getKey() + " reads the Level local");
            }
        }

        MethodNode map = method(
                "net/minecraft/world/item/crafting/MapExtendingRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z");
        if (!readsSlot(map, LEVEL_SLOT)) {
            throw new AssertionError("MapExtendingRecipe.matches no longer reads Level");
        }
        if (!"e5914ed7eff9fad5ad32d043701d296ac03d73374a1ad2d0ce26921b20f2e2ad".equals(instructionHash(map))) {
            throw new AssertionError("MapExtendingRecipe.matches bytecode drifted");
        }

        ClassNode server = node("net/minecraft/server/MinecraftServer.class");
        MethodNode reload = null;
        for (MethodNode method : server.methods) {
            if ("lambda$reloadResources$4".equals(method.name)) {
                reload = method;
                break;
            }
        }
        if (reload == null) {
            throw new AssertionError("MinecraftServer reload lambda disappeared");
        }
        int tags = -1;
        int finalize = -1;
        int index = 0;
        for (AbstractInsnNode insn = reload.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                if ("updateComponentsAndStaticRegistryTags".equals(call.name)) {
                    tags = index;
                }
                if ("finalizeRecipeLoading".equals(call.name)) {
                    finalize = index;
                }
                index++;
            }
        }
        if (tags < 0 || finalize < 0 || tags > finalize) {
            throw new AssertionError("tag publication must precede finalizeRecipeLoading");
        }
    }

    private static ClassNode node(final String resource) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(bytes(resource)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static MethodNode method(final String key) throws Exception {
        int split = key.indexOf('#');
        String resource = key.substring(0, split);
        String signature = key.substring(split + 1);
        int desc = signature.indexOf('(');
        String name = signature.substring(0, desc);
        String descriptor = signature.substring(desc);
        for (MethodNode method : node(resource).methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                return method;
            }
        }
        throw new AssertionError("missing " + key);
    }

    private static String instructionHash(final MethodNode method) throws Exception {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        StringWriter listing = new StringWriter();
        text.print(new PrintWriter(listing));
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(listing.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Reference load of {@code slot}, including the wide {@code aload <n>} form. */
    private static boolean readsSlot(final MethodNode method, final int slot) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == slot) {
                return true;
            }
        }
        return false;
    }

    private static byte[] bytes(final String resource) throws Exception {
        try (InputStream in = RecipeBytecodeContract.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing " + resource);
            }
            return in.readAllBytes();
        }
    }
}
