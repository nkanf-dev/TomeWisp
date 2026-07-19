package dev.openallay.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.EvidenceBearing;
import dev.openallay.recipe.RecipeCatalog;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolResult;
import org.junit.jupiter.api.Test;
import java.util.List;

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
        assertEquals(1, search.value().catalog().recipeCount());
        assertEquals(0, search.value().catalog().semanticGroupCount());
        assertTrue(search.value() instanceof EvidenceBearing);
        assertEquals(
                "minecraft:iron_block",
                search.value().recipes().getFirst().reference().recipeId());

        ToolResult.Success<GetRecipeTool.Output> details = assertInstanceOf(
                ToolResult.Success.class,
                new GetRecipeTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new GetRecipeTool.Input(
                                "minecraft:recipe_manager",
                                GroundedTestFixtures.RECIPE_GENERATION,
                                "minecraft:iron_block")));
        assertEquals(9, details.value().recipe().ingredients().getFirst().count());
        assertEquals(1, details.value().catalog().recipeCount());
        assertTrue(!details.value().evidence().isEmpty());
    }

    @Test
    void distinguishesMalformedAndStaleRecipeReferences() {
        GetRecipeTool tool = new GetRecipeTool();
        ToolResult.Failure<GetRecipeTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(
                        GroundedTestFixtures.fullContext(),
                        new GetRecipeTool.Input(
                                "minecraft:recipe_manager", "not-a-digest", "minecraft:iron_block")));
        assertEquals("invalid_arguments", invalid.code());

        ToolResult.Failure<GetRecipeTool.Output> stale = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(
                        GroundedTestFixtures.fullContext(),
                        new GetRecipeTool.Input(
                                "minecraft:recipe_manager", "f".repeat(64), "minecraft:iron_block")));
        assertEquals("stale_reference", stale.code());
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
        assertEquals(1, usages.value().catalog().recipeCount());

        ToolResult.Failure<SearchRecipesTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                new SearchRecipesTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new SearchRecipesTool.Input(null, null, null, null)));
        assertEquals("invalid_arguments", invalid.code());
    }

    @Test
    void batchesIndependentRecipeTargetsInOneInvocation() {
        ToolResult.Success<SearchRecipesTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                new SearchRecipesTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new SearchRecipesTool.Input(null, null, null, null, List.of(
                                new SearchRecipesTool.Query(
                                        null, "minecraft:iron_block", null, null),
                                new SearchRecipesTool.Query(
                                        null, "minecraft:missing", null, null)))));

        assertEquals(2, result.value().batches().size());
        assertEquals(1, result.value().batches().get(0).recipes().size());
        assertTrue(result.value().batches().get(1).recipes().isEmpty());
        assertEquals(List.of(0, 1),
                result.value().batches().stream().map(SearchRecipesTool.QueryResult::index).toList());
    }
}
