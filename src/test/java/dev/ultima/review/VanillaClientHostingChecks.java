package dev.ultima.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ultima.config.UltimaModules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Static contract for vanilla-client-compatible server hosting: Ultima must not
 * require a matching client install, must not register custom packets, and must
 * keep render Mixins client-only while simulation Mixins stay common.
 */
final class VanillaClientHostingChecks {
    private static final Path FABRIC_MOD_JSON = Path.of("src/main/resources/fabric.mod.json");
    private static final Path COMMON_MIXINS = Path.of("src/main/resources/ultima.mixins.json");
    private static final Path CLIENT_MIXINS = Path.of("src/client/resources/ultima.client.mixins.json");
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path CLIENT_JAVA = Path.of("src/client/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    private static final List<String> FORBIDDEN_NETWORK_TOKENS = List.of(
            "PayloadTypeRegistry",
            "ServerPlayNetworking",
            "ClientPlayNetworking",
            "ServerConfigurationNetworking",
            "ClientConfigurationNetworking",
            "ServerLoginNetworking",
            "ClientLoginNetworking",
            "CustomPayload",
            "CustomPacketPayload",
            "PayloadType",
            "PayloadCodec",
            "registerGlobalReceiver",
            "ModProtocol",
            "net.fabricmc.fabric.api.networking");

    private static final Set<String> SIMULATION_MODULES = Set.of(
            "entity_section_lookup",
            "block_collision_shape",
            "collision_shell_skip",
            "supporting_block_shape_skip",
            "full_cube_move",
            "cursor_step");

    private static final Set<String> CLIENT_RENDER_MODULES = Set.of(
            "client_benchmark",
            "terrain_metrics",
            "retained_terrain",
            "render_snapshot",
            "java_mesher",
            "section_task_queue",
            "rgss_endpoint",
            "temporal");

    private VanillaClientHostingChecks() {
    }

    static void run() {
        JsonObject fabricMod = readJson(FABRIC_MOD_JSON);
        JsonObject commonMixins = readJson(COMMON_MIXINS);
        JsonObject clientMixins = readJson(CLIENT_MIXINS);

        testFabricModAllowsEitherSideWithoutMatching(fabricMod);
        testEntrypointsAreSideSplit(fabricMod);
        testMixinEnvironments(fabricMod, commonMixins, clientMixins);
        testModuleSidesMatchMixinConfigs(commonMixins, clientMixins);
        testNoCustomNetworkingOrRegistries();
        testNoServerDataPackContent();
        System.out.println("Vanilla-client hosting contract checks passed.");
    }

    private static void testFabricModAllowsEitherSideWithoutMatching(final JsonObject fabricMod) {
        assertEquals("ultima", fabricMod.get("id").getAsString(), "mod id");
        assertEquals("*", fabricMod.get("environment").getAsString(),
                "environment * means Ultima may load on a host client or dedicated server; "
                        + "it is not a both-sides-required handshake");
        assertFalse(fabricMod.has("jars"), "no nested jars that could inject extra network contracts");
        assertFalse(fabricMod.has("accessWidener"), "no class tweaker that would desync vanilla clients");

        JsonObject depends = fabricMod.getAsJsonObject("depends");
        assertTrue(depends != null && depends.has("fabricloader"), "host still needs Fabric Loader");
        assertFalse(depends.has("ultima"), "Ultima must not depend on itself as a remote contract");
        String description = fabricMod.get("description").getAsString().toLowerCase(Locale.ROOT);
        assertTrue(description.contains("vanilla"),
                "fabric.mod.json description must state vanilla-client hosting");
    }

    private static void testEntrypointsAreSideSplit(final JsonObject fabricMod) {
        JsonObject entrypoints = fabricMod.getAsJsonObject("entrypoints");
        assertTrue(entrypoints.has("main"), "main entrypoint initializes dedicated + integrated server");
        assertTrue(entrypoints.has("client"), "client entrypoint is the Fabric client-only slot");
        assertFalse(entrypoints.has("server"),
                "a dedicated-server-only entrypoint would skip the integrated-server / LAN host path");

        List<String> main = stringEntrypoints(entrypoints.getAsJsonArray("main"));
        List<String> client = stringEntrypoints(entrypoints.getAsJsonArray("client"));
        assertTrue(main.contains("dev.ultima.Ultima"), "common initializer");
        assertTrue(client.contains("dev.ultima.client.UltimaClient"), "client initializer");
        for (String value : client) {
            assertTrue(value.startsWith("dev.ultima.client."),
                    "client entrypoints must stay in the client package: " + value);
        }
        for (String value : main) {
            assertFalse(value.startsWith("dev.ultima.client."),
                    "main must not classload client packages on a dedicated server: " + value);
        }
    }

    private static void testMixinEnvironments(
            final JsonObject fabricMod,
            final JsonObject commonMixins,
            final JsonObject clientMixins) {
        JsonArray mixins = fabricMod.getAsJsonArray("mixins");
        assertEquals(2, mixins.size(), "exactly one common and one client mixin config");

        JsonObject commonEntry = null;
        JsonObject clientEntry = null;
        for (JsonElement element : mixins) {
            assertTrue(element.isJsonObject(),
                    "mixin configs must be objects so environment is explicit, not an implied default");
            JsonObject entry = element.getAsJsonObject();
            String config = entry.get("config").getAsString();
            String environment = entry.get("environment").getAsString();
            if ("ultima.mixins.json".equals(config)) {
                commonEntry = entry;
                assertEquals("*", environment, "simulation Mixins apply on dedicated and integrated servers");
            } else if ("ultima.client.mixins.json".equals(config)) {
                clientEntry = entry;
                assertEquals("client", environment, "render Mixins must not load on a dedicated server");
            } else {
                throw new AssertionError("unexpected mixin config " + config);
            }
        }
        assertTrue(commonEntry != null, "common mixin config is declared");
        assertTrue(clientEntry != null, "client mixin config is declared");

        assertTrue(commonMixins.has("mixins"), "common config lists shared simulation Mixins");
        assertFalse(commonMixins.has("client"), "common config must not hide client Mixins");
        assertTrue(clientMixins.has("client"), "client config lists client Mixins");
        assertFalse(clientMixins.has("mixins") && !clientMixins.getAsJsonArray("mixins").isEmpty(),
                "client config must not apply Mixins on the dedicated server");
    }

    private static void testModuleSidesMatchMixinConfigs(
            final JsonObject commonMixins,
            final JsonObject clientMixins) {
        Set<String> commonModules = modulesFromMixinArray(commonMixins.getAsJsonArray("mixins"));
        Set<String> clientModules = modulesFromMixinArray(clientMixins.getAsJsonArray("client"));

        assertEquals(SIMULATION_MODULES, commonModules, "common Mixins are exactly the simulation set");
        assertEquals(CLIENT_RENDER_MODULES, clientModules, "client Mixins are exactly the render/instrumentation set");

        for (UltimaModules.Module module : UltimaModules.all()) {
            if (SIMULATION_MODULES.contains(module.key())) {
                assertFalse(module.clientOnly(), module.key() + " must run on a dedicated server");
            } else if (CLIENT_RENDER_MODULES.contains(module.key())) {
                assertTrue(module.clientOnly(), module.key() + " must be client-only");
            } else {
                throw new AssertionError("unclassified module " + module.key());
            }
        }
    }

    private static void testNoCustomNetworkingOrRegistries() {
        List<Path> roots = List.of(MAIN_JAVA, CLIENT_JAVA);
        List<String> hits = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    String source = readString(path);
                    for (String token : FORBIDDEN_NETWORK_TOKENS) {
                        if (source.contains(token)) {
                            hits.add(path + " contains " + token);
                        }
                    }
                    if (source.contains("Registry.register")
                            || source.contains("BuiltInRegistries")
                            || source.contains("FabricItem")
                            || source.contains("FabricBlock")) {
                        hits.add(path + " registers custom content that would fail registry sync");
                    }
                });
            } catch (IOException e) {
                throw new AssertionError("could not scan " + root, e);
            }
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("vanilla clients would be rejected:\n" + String.join("\n", hits));
        }
    }

    private static void testNoServerDataPackContent() {
        Path data = MAIN_RESOURCES.resolve("data");
        assertFalse(Files.exists(data), "no data-pack registries that Fabric would sync to clients");
        Path assets = MAIN_RESOURCES.resolve("assets");
        assertFalse(Files.exists(assets), "common assets would ship on dedicated servers without need");
    }

    private static List<String> stringEntrypoints(final JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            } else {
                values.add(element.getAsJsonObject().get("value").getAsString());
            }
        }
        return values;
    }

    private static Set<String> modulesFromMixinArray(final JsonArray array) {
        Set<String> modules = new LinkedHashSet<>();
        for (JsonElement element : array) {
            String className = element.getAsString();
            int firstDot = className.indexOf('.');
            if (firstDot <= 0) {
                throw new AssertionError("mixin class is not packaged by module: " + className);
            }
            modules.add(className.substring(0, firstDot));
        }
        return modules;
    }

    private static JsonObject readJson(final Path path) {
        return JsonParser.parseString(readString(path)).getAsJsonObject();
    }

    private static String readString(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(final boolean value, final String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final int expected, final int actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
