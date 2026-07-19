package dev.openallay.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.openallay.context.RecipeReference;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SemanticReferenceValidatorTest {
    private static final UUID REQUEST =
            UUID.fromString("911d107c-7202-4c4b-9c95-191b5d33d563");
    private static final RecipeReference RECIPE = new RecipeReference(
            "minecraft:recipe_manager",
            GroundedTestFixtures.RECIPE_GENERATION,
            "minecraft:iron_block");

    @Test
    void rawResourcesArePresentationOnlyButSameRequestHandlesAreGrounded() {
        SemanticReferenceIndex index = index();
        SemanticReferenceValidator validator = new SemanticReferenceValidator();

        SemanticReference raw = validator.validate(
                "[[tw:item|minecraft:diamond|Diamond]]", index).reference();
        SemanticReference returned = validator.validate(
                "[[tw:item|minecraft:iron_block|Iron block]]", index).reference();
        SemanticReference recipe = validator.validate(
                "[[tw:recipe|" + RecipeSemanticHandle.encode(RECIPE) + "|Recipe]]", index)
                .reference();

        assertFalse(raw.grounded());
        assertTrue(returned.grounded());
        assertEquals("tool-1", returned.originInvocationId());
        assertTrue(recipe.grounded());
    }

    @Test
    void foreignOrMalformedStableHandlesRemainInvalid() {
        SemanticReferenceValidator validator = new SemanticReferenceValidator();
        SemanticReferenceIndex empty = SemanticReferenceIndex.empty(REQUEST);

        assertEquals("semantic_reference_unresolved", validator.validate(
                "[[tw:recipe|" + RecipeSemanticHandle.encode(RECIPE) + "]]", empty)
                .failureCode());
        assertEquals("semantic_reference_unsupported", validator.validate(
                "[[tw:command|say hi]]", empty).failureCode());
        assertEquals("semantic_content_invalid", validator.validate(
                "[[tw:item|minecraft:stone|]]", empty).failureCode());
    }

    @Test
    void parserConvertsOnlyValidatedTokensToReferenceNodes() {
        SemanticDocument document = new SemanticMessageParser().parse(
                "Use [[tw:item|minecraft:iron_block|Iron block]] and "
                        + "[[tw:source|missing:source|Missing]].",
                index());
        SemanticBlock.Paragraph paragraph = assertInstanceOf(
                SemanticBlock.Paragraph.class, document.blocks().getFirst());

        assertTrue(paragraph.content().stream().anyMatch(SemanticInline.Reference.class::isInstance));
        assertTrue(paragraph.content().stream()
                .filter(SemanticInline.Text.class::isInstance)
                .map(SemanticInline.Text.class::cast)
                .anyMatch(value -> value.text().contains("[[tw:source")));
        assertEquals(1, document.diagnostics().size());
    }

    @Test
    void knowledgeSourceListIdsBecomeSameRequestSourceReferences() {
        JsonObject source = new JsonObject();
        source.addProperty("id", "patchouli:resources");
        com.google.gson.JsonArray sources = new com.google.gson.JsonArray();
        sources.add(source);
        JsonObject value = new JsonObject();
        value.add("sources", sources);
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.add("value", value);
        GuideToolActivity activity = new GuideToolActivity(
                "tool-sources",
                0,
                "openallay:list_knowledge_sources",
                GuideToolStatus.SUCCEEDED,
                normalized,
                List.of(),
                List.of());

        SemanticReferenceIndex index = SemanticReferenceIndex.from(
                REQUEST, List.of(new GuideTimelineEntry.Tool(0, activity)));
        SemanticReference reference = new SemanticReferenceValidator().validate(
                "[[tw:source|patchouli:resources|Patchouli resources]]", index).reference();

        assertTrue(reference.grounded());
        assertEquals("tool-sources", reference.originInvocationId());
    }

    @Test
    void allRegisteredInlineKindsHaveExplicitGroundingSemantics() {
        SemanticReferenceValidator validator = new SemanticReferenceValidator();
        SemanticReferenceIndex index = index();
        List<String> raw = List.of(
                "[[tw:item|minecraft:stone]]",
                "[[tw:block|minecraft:stone]]",
                "[[tw:fluid|minecraft:water]]",
                "[[tw:entity|minecraft:pig]]",
                "[[tw:biome|minecraft:plains]]",
                "[[tw:dimension|minecraft:overworld]]",
                "[[tw:tag|#minecraft:logs]]",
                "[[tw:key|key.openallay.open_guide]]");
        for (String token : raw) {
            SemanticReference reference = validator.validate(token, index).reference();
            assertFalse(reference.grounded(), token);
        }
        for (String token : List.of(
                "[[tw:recipe|" + RecipeSemanticHandle.encode(RECIPE) + "]]",
                "[[tw:source|minecraft:recipe_manager]]",
                "[[tw:evidence|minecraft:recipe_manager]]")) {
            assertTrue(validator.validate(token, index).reference().grounded(), token);
        }
    }

    static SemanticReferenceIndex index() {
        JsonObject reference = new JsonObject();
        reference.addProperty("sourceId", RECIPE.sourceId());
        reference.addProperty("generation", RECIPE.generation());
        reference.addProperty("recipeId", RECIPE.recipeId());
        JsonObject stack = new JsonObject();
        stack.addProperty("itemId", "minecraft:iron_block");
        JsonObject recipe = new JsonObject();
        recipe.add("reference", reference);
        recipe.add("stack", stack);
        JsonObject value = new JsonObject();
        value.add("recipe", recipe);
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.add("value", value);
        GuideToolActivity activity = new GuideToolActivity(
                "tool-1",
                0,
                "openallay:get_recipe",
                GuideToolStatus.SUCCEEDED,
                normalized,
                List.of(),
                List.of(new GuideSource(
                        "openallay:get_recipe", GroundedTestFixtures.serverEvidence())));
        return SemanticReferenceIndex.from(
                REQUEST, List.of(new GuideTimelineEntry.Tool(0, activity)));
    }

    static RecipeReference recipe() {
        return RECIPE;
    }

    static UUID request() {
        return REQUEST;
    }
}
