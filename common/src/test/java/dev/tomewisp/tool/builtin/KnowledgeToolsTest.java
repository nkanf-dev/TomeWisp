package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KnowledgeToolsTest {
    private static final RecipeEntrySnapshot IRON_BLOCK =
            GroundedTestFixtures.ironBlockRecipe();

    @Test
    void resolvesAllMatchingRegistryKindsWithoutTruncation() {
        ToolResult.Success<ResolveResourceTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                new ResolveResourceTool().invoke(fullContext(), new ResolveResourceTool.Input("minecraft:stone", null)));

        assertTrue(result.value().exists());
        assertEquals(List.of("block", "item"), result.value().matches().stream()
                .map(ResolveResourceTool.Match::kind)
                .toList());
    }

    @Test
    void validUnknownResourceIsSuccessfulAndAbsent() {
        ToolResult.Success<ResolveResourceTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                new ResolveResourceTool().invoke(
                        fullContext(), new ResolveResourceTool.Input(
                                "example:missing", ResolveResourceTool.Kind.item)));

        assertFalse(result.value().exists());
        assertTrue(result.value().matches().isEmpty());
    }

    @Test
    void rejectsBlankQueryAndMissingRegistryContext() {
        ToolResult.Failure<ResolveResourceTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                new ResolveResourceTool().invoke(
                        fullContext(), new ResolveResourceTool.Input(" ", null)));
        assertEquals("invalid_arguments", invalid.code());

        ToolResult.Failure<ResolveResourceTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                new ResolveResourceTool().invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new ResolveResourceTool.Input("minecraft:stone", null)));
        assertEquals("missing_context", missing.code());
    }

    @Test
    void resolvesLocalizedNamesAndIdentifierTokensDeterministically() {
        ToolResult.Success<ResolveResourceTool.Output> localized = assertInstanceOf(
                ToolResult.Success.class,
                new ResolveResourceTool().invoke(
                        fullContext(), new ResolveResourceTool.Input("Stone", null)));
        assertEquals("minecraft:stone", localized.value().matches().getFirst().id());

        ToolResult.Success<ResolveResourceTool.Output> path = assertInstanceOf(
                ToolResult.Success.class,
                new ResolveResourceTool().invoke(
                        fullContext(), new ResolveResourceTool.Input("iron block", null)));
        assertEquals("minecraft:iron_block", path.value().matches().getFirst().id());
    }

    @Test
    void findsEveryRecipeForOutputAndReportsMissingContext() {
        ToolResult.Success<FindRecipesTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                new FindRecipesTool().invoke(
                        fullContext(), new FindRecipesTool.Input("minecraft:iron_block")));
        assertEquals(List.of(IRON_BLOCK), result.value().recipes());

        ToolResult.Failure<FindRecipesTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                new FindRecipesTool().invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new FindRecipesTool.Input("minecraft:iron_block")));
        assertEquals("missing_context", missing.code());
    }

    private static ToolInvocationContext fullContext() {
        return GroundedTestFixtures.fullContext();
    }
}
