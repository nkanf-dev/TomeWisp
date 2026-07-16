package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.BlockPositionSnapshot;
import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.IngredientSlotSnapshot;
import dev.tomewisp.context.InventorySlotSnapshot;
import dev.tomewisp.context.ItemStackSnapshot;
import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.RegistrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class KnowledgeToolsTest {
    private static final RecipeEntrySnapshot IRON_BLOCK = new RecipeEntrySnapshot(
            "minecraft:iron_block",
            "minecraft:crafting",
            List.of(new IngredientSlotSnapshot(List.of("minecraft:iron_ingot"))),
            List.of(new ItemStackSnapshot("minecraft:iron_block", 1, "Block of Iron")),
            "minecraft:iron_block");

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
                        fullContext(), new ResolveResourceTool.Input("example:missing", "item")));

        assertFalse(result.value().exists());
        assertTrue(result.value().matches().isEmpty());
    }

    @Test
    void rejectsInvalidResourceKindAndMissingRegistryContext() {
        ToolResult.Failure<ResolveResourceTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                new ResolveResourceTool().invoke(
                        fullContext(), new ResolveResourceTool.Input("minecraft:stone", "fluid")));
        assertEquals("invalid_arguments", invalid.code());

        ToolResult.Failure<ResolveResourceTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                new ResolveResourceTool().invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new ResolveResourceTool.Input("minecraft:stone", null)));
        assertEquals("missing_context", missing.code());
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

    @Test
    void returnsCompletePlayerOrRequiresOne() {
        ToolResult.Success<PlayerContextTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                new PlayerContextTool().invoke(fullContext(), new PlayerContextTool.Input()));
        assertEquals(2, result.value().player().inventory().size());

        ToolResult.Failure<PlayerContextTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                new PlayerContextTool().invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new PlayerContextTool.Input()));
        assertEquals("player_required", missing.code());
    }

    private static ToolInvocationContext fullContext() {
        UUID playerId = UUID.fromString("54a6fbd8-c548-4c4d-b9a7-7b9e310d2b71");
        PlayerSnapshot player = new PlayerSnapshot(
                playerId,
                "Builder",
                "minecraft:overworld",
                new BlockPositionSnapshot(1, 64, 2),
                "survival",
                new ItemStackSnapshot("minecraft:iron_ingot", 4, "Iron Ingot"),
                ItemStackSnapshot.empty(),
                List.of(
                        new InventorySlotSnapshot(
                                0, new ItemStackSnapshot("minecraft:iron_ingot", 4, "Iron Ingot")),
                        new InventorySlotSnapshot(1, ItemStackSnapshot.empty())));
        RegistrySnapshot registries = new RegistrySnapshot(List.of(
                new RegistryEntrySnapshot(
                        "minecraft:stone", "block", "Stone", "minecraft", "minecraft:stone"),
                new RegistryEntrySnapshot(
                        "minecraft:stone", "item", "Stone", "minecraft", "minecraft:stone")));
        return new ToolInvocationContext(
                "test",
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, playerId, "Builder", true),
                Optional.of(player),
                Optional.of(registries),
                Optional.of(new RecipeSnapshot(List.of(IRON_BLOCK))),
                new ContextMetrics(2, 1, 2, 0, 0));
    }
}
