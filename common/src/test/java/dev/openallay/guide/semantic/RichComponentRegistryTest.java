package dev.openallay.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.RecipeReference;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RichComponentRegistryTest {
    private static final String NODE = "a".repeat(64);
    private final RichComponentRegistry registry = RichComponentRegistry.builtins();
    private final SemanticReferenceIndex references = SemanticReferenceValidatorTest.index();

    @Test
    void decodesEveryRegisteredVersionOneComponent() {
        RecipeReference recipe = SemanticReferenceValidatorTest.recipe();
        List<String> encoded = List.of(
                envelope("item_row", """
                        {"items":[{"itemId":"minecraft:iron_block","count":2,"label":"Iron"}]}
                        """),
                envelope("recipe_grid", recipe(recipe, "\"label\":\"Iron block\"")),
                envelope("ingredient_check", """
                        {"ingredients":[{"itemId":"minecraft:iron_block","required":3,"available":2,"label":"Iron"}]}
                        """),
                envelope("craftability_summary", recipe(recipe,
                        "\"craftable\":false,\"conclusive\":true,"
                                + "\"requestedCrafts\":1,\"maximumCrafts\":0")),
                envelope("progress_steps", """
                        {"steps":[{"id":"gather","label":"Gather materials","state":"ACTIVE"}]}
                        """),
                envelope("source_summary", """
                        {"sources":[{"sourceId":"minecraft:recipe_manager","label":"Server"}]}
                        """),
                envelope("status_badge", """
                        {"state":"WARNING","label":"Missing material"}
                        """),
                envelope("choice_group", """
                        {"prompt":"Choose a path","choices":[{"id":"a","label":"First"},{"id":"b","label":"Second"}]}
                        """));

        List<Class<?>> expected = List.of(
                RichComponent.ItemRow.class,
                RichComponent.RecipeGrid.class,
                RichComponent.IngredientCheck.class,
                RichComponent.CraftabilitySummary.class,
                RichComponent.ProgressSteps.class,
                RichComponent.SourceSummary.class,
                RichComponent.StatusBadge.class,
                RichComponent.ChoiceGroup.class);
        for (int index = 0; index < encoded.size(); index++) {
            RichComponentRegistry.Decode result = registry.decode(
                    encoded.get(index), NODE, references);
            assertTrue(result.successful(), "component " + index + " failed");
            assertEquals(expected.get(index), result.component().getClass());
            assertEquals("Readable fallback", result.fallbackText());
        }
    }

    @Test
    void unknownKeysActionsVersionsAndForeignReferencesFailClosed() {
        RecipeReference recipe = SemanticReferenceValidatorTest.recipe();
        List<String> invalid = List.of(
                envelope("item_row", """
                        {"items":[{"itemId":"minecraft:iron_block","count":1,"label":"Iron","command":"/give"}]}
                        """),
                envelope("choice_group", """
                        {"prompt":"Choose","choices":[{"id":"a","label":"https://bad.invalid"}]}
                        """),
                envelope("recipe_grid", recipe(new RecipeReference(
                        recipe.sourceId(), "b".repeat(64), recipe.recipeId()),
                        "\"label\":\"Stale\"")),
                """
                        {"schemaVersion":2,"type":"status_badge","properties":{"state":"INFO","label":"Info"},"fallback":"Readable fallback","narration":"Narration"}
                        """,
                """
                        {"schemaVersion":1,"type":"status_badge","properties":{"state":"INFO","label":"Info"},"fallback":"Readable fallback","narration":"Narration","callback":"java.lang.Runtime"}
                        """);

        for (String value : invalid) {
            RichComponentRegistry.Decode result = registry.decode(value, NODE, references);
            assertFalse(result.successful());
            assertEquals("semantic_component_unsupported", result.failureCode());
            assertFalse(result.fallbackText().isBlank());
        }
    }

    @Test
    void parserUsesFallbackForUnsupportedComponentBlock() {
        String markdown = """
                ```openallay-component
                {"schemaVersion":1,"type":"world_mutation","properties":{"command":"/op"},"fallback":"Cannot show this component","narration":"Unavailable"}
                ```
                """;

        SemanticDocument document = new SemanticMessageParser().parse(markdown, references);

        assertInstanceOf(SemanticBlock.Paragraph.class, document.blocks().getFirst());
        assertEquals("Cannot show this component", document.fallbackText());
        assertEquals("semantic_component_unsupported", document.diagnostics().getFirst().code());
    }

    private static String envelope(String type, String properties) {
        return "{\"schemaVersion\":1,\"type\":\"" + type
                + "\",\"properties\":" + properties.strip()
                + ",\"fallback\":\"Readable fallback\",\"narration\":\"Narration\"}";
    }

    private static String recipe(RecipeReference recipe, String extra) {
        return "{\"sourceId\":\"" + recipe.sourceId()
                + "\",\"generation\":\"" + recipe.generation()
                + "\",\"recipeId\":\"" + recipe.recipeId()
                + "\"," + extra + "}";
    }
}
