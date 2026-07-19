package dev.openallay.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.InventorySlotSnapshot;
import dev.openallay.context.InventorySnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.PlayerSnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

final class InspectInventoryToolTest {
    @Test
    void countsSlotsAndOffhandOnceWithEvidence() {
        InventorySnapshot inventory = new InventorySnapshot(
                List.of(
                        new InventorySlotSnapshot(
                                0, new ItemStackSnapshot("minecraft:iron_ingot", 4, "Iron Ingot")),
                        new InventorySlotSnapshot(
                                1, new ItemStackSnapshot("minecraft:iron_ingot", 2, "Iron Ingot"))),
                2,
                0,
                0,
                new ItemStackSnapshot("minecraft:torch", 3, "Torch"),
                true,
                GroundedTestFixtures.playerEvidence());
        PlayerSnapshot base = GroundedTestFixtures.player();
        ToolInvocationContext baseContext = GroundedTestFixtures.fullContext();
        ToolInvocationContext context = new ToolInvocationContext(
                baseContext.correlationId(),
                baseContext.capturedAt(),
                baseContext.caller(),
                Optional.of(new PlayerSnapshot(
                        base.uuid(),
                        base.displayName(),
                        base.dimension(),
                        base.position(),
                        base.gameMode(),
                        inventory,
                        base.evidence())),
                baseContext.registries(),
                baseContext.recipes(),
                baseContext.metrics());

        ToolResult.Success<InspectInventoryTool.Output> success = assertInstanceOf(
                ToolResult.Success.class,
                new InspectInventoryTool().invoke(context, new InspectInventoryTool.Input()));

        assertEquals(
                new TreeMap<>(java.util.Map.of(
                        "minecraft:iron_ingot", 6L,
                        "minecraft:torch", 3L)),
                success.value().counts());
        assertInstanceOf(EvidenceBearing.class, success.value());
        assertEquals(DataAuthority.SERVER_AUTHORITATIVE, success.value().evidence().getFirst().authority());
        assertThrows(
                UnsupportedOperationException.class,
                () -> success.value().counts().put("minecraft:stone", 1L));
    }

    @Test
    void requiresPlayerContext() {
        ToolResult.Failure<InspectInventoryTool.Output> failure = assertInstanceOf(
                ToolResult.Failure.class,
                new InspectInventoryTool().invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new InspectInventoryTool.Input()));

        assertEquals("player_required", failure.code());
    }
}
