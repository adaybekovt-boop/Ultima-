package dev.ultima.review;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ultima.config.settings.ApplyPolicy;
import dev.ultima.config.settings.FsrPresetRowView;
import dev.ultima.config.settings.ModuleDisableMessages;
import dev.ultima.config.settings.ModuleSettingSpec;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.SettingsRowView;
import dev.ultima.config.settings.UltimaSettingsCatalog;
import dev.ultima.fsr.FsrQualityPreset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the shipped language files in step with the code. English is owned by the Java classes
 * (the server-side command output needs it too), so {@code en_us.json} must match them exactly,
 * every other language must carry the same keys and placeholders, and every key the client code
 * names must exist.
 */
final class LocalizationChecks {
    private static final Path LANG_DIR = Path.of("src/client/resources/assets/ultima/lang");
    private static final Path CLIENT_SOURCE = Path.of("src/client/java");
    private static final List<String> LANGUAGES = List.of("ru_ru");

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?s");
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\"(ultima\\.(?:settings|category|module|apply|status|fsr)\\.[a-z0-9_.]*[a-z0-9_])\"");

    private LocalizationChecks() {
    }

    static void run() {
        Map<String, String> expectedEnglish = expectedEnglish();
        Map<String, String> english = load("en_us");

        for (Map.Entry<String, String> entry : expectedEnglish.entrySet()) {
            assertEquals(entry.getValue(), english.get(entry.getKey()), "en_us text for " + entry.getKey());
        }
        assertEquals(new TreeSet<>(expectedEnglish.keySet()), new TreeSet<>(english.keySet()),
                "en_us must contain exactly the keys the code uses");

        for (String language : LANGUAGES) {
            checkLanguage(language, english);
        }
        checkClientKeyLiterals(english.keySet());
        System.out.println("Localization checks passed: " + english.size() + " keys, en_us + " + LANGUAGES);
    }

    private static Map<String, String> expectedEnglish() {
        Map<String, String> expected = new LinkedHashMap<>();
        // Literals the screen and the title-screen Mixin pass as fallbacks.
        expected.put("ultima.settings.title", "Ultima Settings");
        expected.put("ultima.settings.title_button", "Ultima...");
        expected.put("ultima.settings.title_button.tooltip", "Ultima optimization modules");
        expected.put("ultima.settings.persist_hint", "Saved to ultima.properties. Mixins apply after a game restart.");
        expected.put("ultima.settings.pending_restart", "Restart the game to apply pending module changes.");
        expected.put("ultima.settings.pending_line", SettingsRowView.PENDING_LINE);
        expected.put("ultima.settings.fsr.preset", FsrPresetRowView.LABEL);

        for (SettingsCategory category : SettingsCategory.values()) {
            expected.put("ultima.category." + category.key(), category.displayName());
        }
        for (ApplyPolicy policy : ApplyPolicy.values()) {
            expected.put("ultima.apply." + policy.key(), policy.warning());
        }
        for (ModuleSettingSpec spec : UltimaSettingsCatalog.all()) {
            expected.put("ultima.module." + spec.key() + ".name", spec.displayName());
            expected.put("ultima.module." + spec.key() + ".tooltip", spec.tooltip());
        }
        for (FsrQualityPreset preset : FsrQualityPreset.values()) {
            expected.put("ultima.fsr.preset." + preset.name().toLowerCase(Locale.ROOT), preset.displayName());
        }
        expected.put("ultima.fsr.preset.tooltip", FsrPresetRowView.TOOLTIP_TEMPLATE);

        for (Map.Entry<String, String> entry : ModuleDisableMessages.staticMessages().entrySet()) {
            expected.put("ultima.status." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : ModuleDisableMessages.knownConflictMessages().entrySet()) {
            expected.put("ultima.status.incompatible_mod." + entry.getKey(), entry.getValue());
        }
        expected.put("ultima.status.incompatible_mod.none", ModuleDisableMessages.NO_INCOMPATIBLE_MOD_MESSAGE);
        expected.put("ultima.status.incompatible_mod.other", ModuleDisableMessages.CONFLICT_OTHER_TEMPLATE);
        expected.put("ultima.status.incompatible_mod.multiple", ModuleDisableMessages.CONFLICT_MULTIPLE_TEMPLATE);
        expected.put("ultima.status.dependency_disabled", ModuleDisableMessages.DEPENDENCY_TEMPLATE);
        expected.put("ultima.status.another_module", ModuleDisableMessages.ANOTHER_MODULE_NAME);
        return expected;
    }

    private static void checkLanguage(final String language, final Map<String, String> english) {
        Map<String, String> translated = load(language);
        assertEquals(new TreeSet<>(english.keySet()), new TreeSet<>(translated.keySet()),
                language + " must carry exactly the en_us keys");
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String text = translated.get(entry.getKey());
            assertTrue(!text.isBlank(), language + " has an empty text for " + entry.getKey());
            assertEquals(placeholders(entry.getValue()), placeholders(text),
                    language + " placeholders for " + entry.getKey());
        }
        for (ModuleSettingSpec spec : UltimaSettingsCatalog.all()) {
            String key = "ultima.module." + spec.key() + ".name";
            assertTrue(!translated.get(key).equals(english.get(key)),
                    language + " left the name of " + spec.key() + " untranslated");
        }
    }

    private static void checkClientKeyLiterals(final Set<String> known) {
        List<String> missing = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(CLIENT_SOURCE)) {
            for (Path path : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher matcher = KEY_LITERAL.matcher(Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!known.contains(matcher.group(1))) {
                        missing.add(path + " uses unknown translation key " + matcher.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not scan " + CLIENT_SOURCE, e);
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    private static Set<String> placeholders(final String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Map<String, String> load(final String language) {
        Path file = LANG_DIR.resolve(language + ".json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> texts = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                texts.put(entry.getKey(), entry.getValue().getAsString());
            }
            return texts;
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
