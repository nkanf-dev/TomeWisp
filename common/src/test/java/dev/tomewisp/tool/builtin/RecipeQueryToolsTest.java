package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.recipe.RecipeCatalog;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import org.junit.jupiter.api.Test;

final class RecipeQueryToolsTest {
    @Test
    void searchesAndGetsRecipeWithEvidence() {
        ToolResult.Success<SearchRecipesTool.Output> search = assertInstanceOf(
                ToolResult.Success.class,
                new SearchRecipesTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new SearchRecipesTool.Input(
                                null, "minecraft:iron_block", null, null)));

        assertEquals(1, search.value().recipes().size());
        assertTrue(search.value() instanceof EvidenceBearing);
        assertEquals(
                "minecraft:iron_block",
                search.value().recipes().getFirst().reference().recipeId());

        ToolResult.Success<GetRecipeTool.Output> details = assertInstanceOf(
                ToolResult.Success.class,
                new GetRecipeTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new GetRecipeTool.Input(
                                "minecraft:recipe_manager", "minecraft:iron_block")));
        assertEquals(9, details.value().recipe().ingredients().getFirst().count());
        assertTrue(!details.value().evidence().isEmpty());
    }

    @Test
    void findsUsageAndRejectsEmptySearch() {
        ToolResult.Success<FindItemUsagesTool.Output> usages = assertInstanceOf(
                ToolResult.Success.class,
                new FindItemUsagesTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new FindItemUsagesTool.Input("minecraft:iron_ingot")));
        assertEquals(
                RecipeCatalog.UsageRole.INPUT,
                usages.value().usages().getFirst().role());

        ToolResult.Failure<SearchRecipesTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                new SearchRecipesTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new SearchRecipesTool.Input(null, null, null, null)));
        assertEquals("invalid_arguments", invalid.code());
    }
}
