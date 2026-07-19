package dev.openallay.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.guide.history.GuideHistoryException;
import dev.openallay.guide.history.GuideHistoryScope;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MinecraftGuideHistoryScopeTest {
    private static final UUID ACTOR =
            UUID.fromString("12a10497-3aa6-459d-b4dd-143a91e68be8");

    @Test
    void detachesNormalizedSingleplayerWorldWithoutRetainingItsPath() {
        Path world = Path.of("build", "run", "saves", "phase-four", "..", "phase-four");

        GuideHistoryScope scope = MinecraftGuideHistoryScope.detached(ACTOR, world, null);

        assertEquals(GuideHistoryScope.Kind.SINGLEPLAYER, scope.kind());
        assertFalse(scope.scopeId().contains("phase-four"));
        assertEquals(scope, MinecraftGuideHistoryScope.detached(
                ACTOR, world.toAbsolutePath().normalize(), null));
    }

    @Test
    void normalizesMultiplayerAddressAndIsolatesActors() {
        GuideHistoryScope first = MinecraftGuideHistoryScope.detached(
                ACTOR, null, " Example.COM:25565 ");
        GuideHistoryScope same = MinecraftGuideHistoryScope.detached(
                ACTOR, null, "example.com:25565");
        GuideHistoryScope anotherActor = MinecraftGuideHistoryScope.detached(
                UUID.fromString("e1be13dc-25b6-437f-876d-64195165a195"),
                null,
                "example.com:25565");

        assertEquals(first, same);
        assertNotEquals(first, anotherActor);
        assertFalse(first.scopeId().contains("example"));
    }

    @Test
    void rejectsMissingOrAmbiguousConnectionState() {
        GuideHistoryException missing = assertThrows(
                GuideHistoryException.class,
                () -> MinecraftGuideHistoryScope.detached(ACTOR, null, null));
        assertEquals("history_scope_unavailable", missing.code());

        GuideHistoryException ambiguous = assertThrows(
                GuideHistoryException.class,
                () -> MinecraftGuideHistoryScope.detached(
                        ACTOR, Path.of("world"), "server.example"));
        assertEquals("history_scope_unavailable", ambiguous.code());
    }
}
