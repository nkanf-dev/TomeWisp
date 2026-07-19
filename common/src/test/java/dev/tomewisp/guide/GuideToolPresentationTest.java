package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuideToolPresentationTest {
    @Test
    void summarizesResolvedResourcesWithoutEmbeddingLocaleText() {
        JsonObject normalized = success();
        JsonObject value = normalized.getAsJsonObject("value");
        JsonArray matches = new JsonArray();
        JsonObject match = new JsonObject();
        match.addProperty("id", "farmersdelight:apple_cider");
        match.addProperty("kind", "item");
        match.addProperty("displayName", "Apple Cider");
        matches.add(match);
        value.add("matches", matches);

        List<GuideToolMessage> messages =
                GuideToolPresentation.messages("tomewisp:resolve_resource", normalized);
        assertEquals(GuideToolMessage.Key.RESOLVE_ONE, messages.getFirst().key());
        assertEquals(
                GuideToolMessage.of(
                        GuideToolMessage.Key.RESOLVE_MATCH,
                        "Apple Cider",
                        "farmersdelight:apple_cider",
                        "item"),
                messages.get(1));
        assertFalse(messages.toString().contains("normalized"));
    }

    @Test
    void invocationProjectionOnlyIncludesAllowlistedIdentifierLikeValues() {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("outputItem", "minecraft:iron_block");
        recipe.addProperty("untrusted", "sk-secret-value");
        assertEquals(
                List.of(GuideToolMessage.of(
                        GuideToolMessage.Key.INVOCATION_SEARCH_RECIPES_EXACT,
                        "minecraft:iron_block")),
                GuideToolInvocationPresentation.messages("tomewisp:search_recipes", recipe));

        JsonObject natural = new JsonObject();
        natural.addProperty("query", "sk-secret-value");
        String projected = GuideToolInvocationPresentation
                .messages("tomewisp:resolve_resource", natural)
                .toString();
        assertFalse(projected.contains("sk-secret-value"));
    }

    @Test
    void collapsedRecipeSummaryUsesPlayerFacingOutputName() {
        JsonObject normalized = JsonParser.parseString("""
                {"status":"success","value":{"recipes":[{
                  "reference":{"sourceId":"viewer:jei","generation":"generation","recipeId":"internal:opaque/hash"},
                  "workstation":"minecraft:crafting_table",
                  "outputs":[{"stack":{"itemId":"minecraft:iron_block","count":1,"displayName":"Block of Iron"}}]
                }]}}
                """).getAsJsonObject();

        List<GuideToolMessage> messages =
                GuideToolPresentation.messages("tomewisp:search_recipes", normalized);

        assertEquals(
                GuideToolMessage.of(GuideToolMessage.Key.RECIPE_ITEM, "Block of Iron"),
                messages.get(1));
        assertFalse(messages.toString().contains("internal:opaque"));
    }

    @Test
    void strictCodecRoundTripsClosedMessagesAndRejectsSchemaDrift() {
        List<GuideToolMessage> expected = List.of(
                GuideToolMessage.of(GuideToolMessage.Key.RECIPE_DETAIL, "minecraft:iron_block"),
                GuideToolMessage.of(
                        GuideToolMessage.Key.RECIPE_OUTPUT,
                        "minecraft:iron_block",
                        "1"));
        assertEquals(expected, GuideToolMessageCodec.decode(GuideToolMessageCodec.encode(expected)));

        assertThrows(IllegalArgumentException.class, () -> GuideToolMessageCodec.decode(
                JsonParser.parseString("[{\"key\":\"RESULT_COMPLETED\",\"arguments\":[],\"extra\":true}]")));
        assertThrows(IllegalArgumentException.class, () -> GuideToolMessageCodec.decode(
                JsonParser.parseString("[{\"key\":\"FUTURE_KEY\",\"arguments\":[]}]")));
        assertThrows(IllegalArgumentException.class, () -> GuideToolMessageCodec.decode(
                JsonParser.parseString("[{\"key\":\"RESULT_COMPLETED\",\"arguments\":[1]}]")));
        assertThrows(IllegalArgumentException.class, () -> GuideToolMessageCodec.decode(
                JsonParser.parseString("[{\"key\":\"RESULT_COMPLETED\",\"arguments\":[\"bad\\nvalue\"]}]")));
    }

    @Test
    void everyClosedMessageHasEnglishAndSimplifiedChineseTranslations() {
        JsonObject english = language("en_us");
        JsonObject chinese = language("zh_cn");
        for (GuideToolMessage.Key key : GuideToolMessage.Key.values()) {
            assertTrue(english.has(key.translationKey()), "missing en_us: " + key.translationKey());
            assertTrue(chinese.has(key.translationKey()), "missing zh_cn: " + key.translationKey());
        }
    }

    private static JsonObject language(String locale) {
        try (var input = GuideToolPresentationTest.class.getResourceAsStream(
                        "/assets/tomewisp/lang/" + locale + ".json");
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Unable to load " + locale, exception);
        }
    }

    private static JsonObject success() {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.add("value", new JsonObject());
        return normalized;
    }
}
