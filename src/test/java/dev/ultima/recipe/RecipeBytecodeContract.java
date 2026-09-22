package dev.ultima.recipe;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the 26.2 {@code matches} bytecode Ultima's allowlist assumes, and the reload order that
 * publishes tags before {@code RecipeManager.finalizeRecipeLoading}.
 */
final class RecipeBytecodeContract {
    private RecipeBytecodeContract() {
    }

    static void run() throws Exception {
        Map<String, String> typedMatches = Map.of(
                "net/minecraft/world/item/crafting/ShapedRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "5ff5211619d66c503893d01208daf964401a5840bb667256590d36529d343939",
                "net/minecraft/world/item/crafting/ShapelessRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "30eab18db5ee41b30461383458e1cfbb0338904d85d38812e6e026b73ae3deef",
                "net/minecraft/world/item/crafting/RepairItemRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "acf9356c7ecde739dc7c93eb669ce89a40ffd5e197203a4a35f92c458bfc7e91",
                "net/minecraft/world/item/crafting/SingleItemRecipe.class#matches(Lnet/minecraft/world/item/crafting/SingleRecipeInput;Lnet/minecraft/world/level/Level;)Z",
                "848544d22021ced11d6eb8468dc5f7ef62964931eda3259a3404ad0298d3b037",
                "net/minecraft/world/item/crafting/SmithingRecipe.class#matches(Lnet/minecraft/world/item/crafting/SmithingRecipeInput;Lnet/minecraft/world/level/Level;)Z",
                "89e011535b33defa633ef6591187c3a4c6ccfc53e80e3f11c627564b8cf97592",
                "net/minecraft/world/item/crafting/TransmuteRecipe.class#matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
                "9d3d21c66aacdbc03e4f47cc69c178ae6ffa3bc001016601df1463ae53a9800d",
                "net/minecraft/world/item/crafting/Ingredient.class#test(Lnet/minecraft/world/item/ItemStack;)Z",
                "f6790b23a3ae5320d306fe87e001e3a6671da357e6797f946d3f62b6189f6692");
        for (Map.Entry<String, String> entry : typedMatches.entrySet()) {
            int split = entry.getKey().indexOf('#');
            String resource = entry.getKey().substring(0, split);
            String method = entry.getKey().substring(split + 1);
            int desc = method.indexOf('(');
            CodeView code = codeAttribute(resource, method.substring(0, desc), method.substring(desc));
            String hash = sha256(code.attribute());
            if (!entry.getValue().equals(hash)) {
                throw new AssertionError(entry.getKey() + " bytecode drifted: " + hash);
            }
            if (count(code.body(), (byte) 0x2c) != 0) {
                throw new AssertionError(entry.getKey() + " reads the Level local");
            }
        }

        CodeView map = codeAttribute(
                "net/minecraft/world/item/crafting/MapExtendingRecipe.class",
                "matches",
                "(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z");
        if (count(map.body(), (byte) 0x2c) == 0) {
            throw new AssertionError("MapExtendingRecipe.matches no longer reads Level");
        }
        if (!"3aa50a74a43010092ff40277f020a412ea3505ca5ba0ba73507a65bc7154c68f".equals(sha256(map.attribute()))) {
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
        new ClassReader(bytes(resource)).accept(node, 0);
        return node;
    }

    private static byte[] bytes(final String resource) throws Exception {
        try (InputStream in = RecipeBytecodeContract.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static CodeView codeAttribute(final String resource, final String name, final String descriptor)
            throws Exception {
        byte[] data = bytes(resource);
        int index = 8;
        int count = u2(data, index);
        index += 2;
        String[] utf8 = new String[count];
        int cursor = 1;
        while (cursor < count) {
            int tag = data[index++] & 0xff;
            switch (tag) {
                case 1 -> {
                    int length = u2(data, index);
                    index += 2;
                    utf8[cursor] = new String(data, index, length, java.nio.charset.StandardCharsets.UTF_8);
                    index += length;
                }
                case 7, 8, 16, 19, 20 -> index += 2;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> index += 4;
                case 5, 6 -> {
                    index += 8;
                    cursor++;
                }
                case 15 -> index += 3;
                default -> throw new AssertionError("bad class tag " + tag);
            }
            cursor++;
        }
        index += 2;
        index += 2;
        index += 2;
        int interfaces = u2(data, index);
        index += 2 + 2 * interfaces;
        int fields = u2(data, index);
        index += 2;
        for (int field = 0; field < fields; field++) {
            index += 6;
            int attributes = u2(data, index);
            index += 2;
            for (int attribute = 0; attribute < attributes; attribute++) {
                index += 2;
                int length = u4(data, index);
                index += 4 + length;
            }
        }
        int methods = u2(data, index);
        index += 2;
        for (int method = 0; method < methods; method++) {
            index += 2;
            int nameIndex = u2(data, index);
            index += 2;
            int descIndex = u2(data, index);
            index += 2;
            int attributes = u2(data, index);
            index += 2;
            boolean match = name.equals(utf8[nameIndex]) && descriptor.equals(utf8[descIndex]);
            for (int attribute = 0; attribute < attributes; attribute++) {
                int attributeName = u2(data, index);
                index += 2;
                int length = u4(data, index);
                index += 4;
                if (match && "Code".equals(utf8[attributeName])) {
                    int codeLength = u4(data, index + 4);
                    return new CodeView(
                            java.util.Arrays.copyOfRange(data, index, index + length),
                            java.util.Arrays.copyOfRange(data, index + 8, index + 8 + codeLength));
                }
                index += length;
            }
        }
        throw new AssertionError("missing " + resource + " " + name + descriptor);
    }

    private static int u2(final byte[] data, final int index) {
        return ((data[index] & 0xff) << 8) | (data[index + 1] & 0xff);
    }

    private static int u4(final byte[] data, final int index) {
        return ((data[index] & 0xff) << 24)
                | ((data[index + 1] & 0xff) << 16)
                | ((data[index + 2] & 0xff) << 8)
                | (data[index + 3] & 0xff);
    }

    private static int count(final byte[] data, final byte value) {
        int found = 0;
        for (byte current : data) {
            if (current == value) {
                found++;
            }
        }
        return found;
    }

    private static String sha256(final byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private record CodeView(byte[] attribute, byte[] body) {
    }
}
