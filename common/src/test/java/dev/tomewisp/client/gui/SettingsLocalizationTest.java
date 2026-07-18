package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SettingsLocalizationTest {
    private static final Set<String> REQUIRED = Set.of(
            "screen.tomewisp.settings.title",
            "screen.tomewisp.settings.short",
            "screen.tomewisp.settings.general",
            "screen.tomewisp.settings.models",
            "screen.tomewisp.settings.knowledge",
            "screen.tomewisp.settings.history",
            "screen.tomewisp.settings.diagnostics",
            "screen.tomewisp.settings.models.add",
            "screen.tomewisp.settings.models.base_url",
            "screen.tomewisp.settings.models.api_key",
            "screen.tomewisp.settings.models.api_key_not_set",
            "screen.tomewisp.settings.models.api_key_saved",
            "screen.tomewisp.settings.models.api_key_replace",
            "screen.tomewisp.settings.models.context_window",
            "screen.tomewisp.settings.models.test",
            "screen.tomewisp.settings.confirm_billable_test",
            "screen.tomewisp.settings.general.debug.label",
            "screen.tomewisp.settings.general.debug.narration",
            "screen.tomewisp.settings.history.action.delete_current",
            "screen.tomewisp.settings.history.action.delete_actor",
            "screen.tomewisp.settings.history.action.reset_database",
            "screen.tomewisp.settings.history.confirm_reset_again_notice",
            "screen.tomewisp.settings.diagnostics.status.ready",
            "screen.tomewisp.settings.diagnostics.metric.pending_writes",
            "screen.tomewisp.settings.diagnostics.debug.narration",
            "screen.tomewisp.settings.diagnostics.debug.database_schema",
            "screen.tomewisp.settings.capability.filter",
            "screen.tomewisp.settings.capability.enabled",
            "screen.tomewisp.settings.capability.disabled",
            "screen.tomewisp.settings.capability.unavailable",
            "screen.tomewisp.settings.capability.help",
            "screen.tomewisp.settings.recipe.title",
            "screen.tomewisp.settings.recipe.visibility_all",
            "screen.tomewisp.settings.recipe.visibility_unlocked",
            "screen.tomewisp.settings.recipe.preferred_auto",
            "screen.tomewisp.settings.recipe.source.minecraft_client_recipe_book",
            "settings.tomewisp.capability.tool.tomewisp_recipes.title",
            "settings.tomewisp.capability.skill.search_guide_books.description");

    @Test
    void englishAndChineseExposeTheSameCompleteSettingsKeys() throws Exception {
        JsonObject english = read("en_us.json");
        JsonObject chinese = read("zh_cn.json");

        assertEquals(english.keySet(), chinese.keySet());
        for (String key : REQUIRED) {
            assertTrue(english.has(key), key);
            assertTrue(chinese.has(key), key);
            assertTrue(!english.get(key).getAsString().isBlank(), key);
            assertTrue(!chinese.get(key).getAsString().isBlank(), key);
        }
    }

    private static JsonObject read(String file) throws Exception {
        Path path = Path.of("src/main/resources/assets/tomewisp/lang").resolve(file);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
