package dev.openallay.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ToolInvocationContextTest {
    @Test
    void preservesCompleteImmutableContext() {
        var slots = new ArrayList<>(List.of(new InventorySlotSnapshot(
                0, new ItemStackSnapshot("minecraft:stone", 64, "Stone"))));
        UUID uuid = UUID.randomUUID();
        var player = new PlayerSnapshot(
                uuid,
                "Player",
                "minecraft:overworld",
                new BlockPositionSnapshot(1, 64, 2),
                "survival",
                new InventorySnapshot(
                        slots,
                        1,
                        0,
                        0,
                        ItemStackSnapshot.empty(),
                        true,
                        GroundedTestFixtures.playerEvidence()),
                GroundedTestFixtures.playerEvidence());
        var context = new ToolInvocationContext(
                "trace:test",
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, uuid, "Player", true),
                Optional.of(player),
                Optional.empty(),
                Optional.empty(),
                new ContextMetrics(1, 0, 1, 0, 0));

        slots.clear();

        assertEquals(1, context.player().orElseThrow().inventory().slots().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.player().orElseThrow().inventory().slots().clear());
    }
}
