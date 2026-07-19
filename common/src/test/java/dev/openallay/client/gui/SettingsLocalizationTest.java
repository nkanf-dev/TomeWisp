package dev.openallay.client.gui;

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
            "screen.openallay.settings.title",
            "screen.openallay.model.client",
            "screen.openallay.model.server",
            "screen.openallay.settings.short",
            "screen.openallay.settings.general",
            "screen.openallay.settings.models",
            "screen.openallay.settings.tools",
            "screen.openallay.settings.skills",
            "screen.openallay.settings.knowledge",
            "screen.openallay.settings.history",
            "screen.openallay.settings.diagnostics",
            "screen.openallay.settings.about",
            "screen.openallay.settings.models.add",
            "screen.openallay.settings.models.base_url",
            "screen.openallay.settings.models.api_key",
            "screen.openallay.settings.models.api_key_not_set",
            "screen.openallay.settings.models.api_key_saved",
            "screen.openallay.settings.models.api_key_environment",
            "screen.openallay.settings.models.api_key_replace",
            "screen.openallay.settings.models.api_key_saved_hint",
            "screen.openallay.settings.models.api_key_environment_hint",
            "screen.openallay.settings.models.api_key_enter_hint",
            "screen.openallay.settings.models.fetch",
            "screen.openallay.settings.models.choose",
            "screen.openallay.settings.models.catalog_title",
            "screen.openallay.settings.models.catalog_empty",
            "screen.openallay.settings.models.catalog_invalid",
            "screen.openallay.settings.models.catalog_failed",
            "screen.openallay.settings.models.context_window",
            "screen.openallay.settings.models.test",
            "screen.openallay.settings.confirm_billable_test",
            "screen.openallay.settings.general.debug.label",
            "screen.openallay.settings.general.assistant_name.label",
            "screen.openallay.settings.general.assistant_name.description",
            "screen.openallay.settings.general.assistant_name.save",
            "screen.openallay.settings.about.title",
            "screen.openallay.settings.about.description",
            "screen.openallay.settings.about.repository",
            "screen.openallay.settings.about.copy_repository",
            "screen.openallay.settings.general.debug.narration",
            "screen.openallay.settings.history.action.delete_current",
            "screen.openallay.settings.history.action.delete_actor",
            "screen.openallay.settings.history.action.reset_database",
            "screen.openallay.settings.history.confirm_reset_again_notice",
            "screen.openallay.settings.diagnostics.status.ready",
            "screen.openallay.settings.diagnostics.metric.pending_writes",
            "screen.openallay.settings.diagnostics.debug.narration",
            "screen.openallay.settings.diagnostics.debug.database_schema",
            "screen.openallay.settings.capability.filter",
            "screen.openallay.settings.capability.enabled",
            "screen.openallay.settings.capability.disabled",
            "screen.openallay.settings.capability.unavailable",
            "screen.openallay.settings.capability.help",
            "screen.openallay.settings.recipe.title",
            "screen.openallay.settings.recipe.visibility_all",
            "screen.openallay.settings.recipe.visibility_unlocked",
            "screen.openallay.settings.recipe.preferred_auto",
            "screen.openallay.settings.recipe.source.minecraft_client_recipe_book",
            "openallay.settings.tools.recipes.title",
            "openallay.settings.tools.guides.description",
            "screen.openallay.settings.tools.source.add_local",
            "screen.openallay.settings.skills.create_override",
            "settings.openallay.capability.tool.openallay_recipes.title",
            "settings.openallay.capability.skill.search_guide_books.description");

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
        Path path = Path.of("src/main/resources/assets/openallay/lang").resolve(file);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
