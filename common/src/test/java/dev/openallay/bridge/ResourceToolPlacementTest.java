package dev.openallay.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class ResourceToolPlacementTest {
    @Test
    void routesClientAndServerOwnedRootsSymmetrically() {
        var game = JsonParser.parseString("{\"paths\":[\"/game/options\"]}").getAsJsonObject();
        var world = JsonParser.parseString("{\"paths\":[\"/world/dimension\"]}").getAsJsonObject();

        assertEquals(
                ResourceToolPlacement.Decision.LOCAL,
                ResourceToolPlacement.decide(
                        "openallay:resource_read", game, ResourceToolPlacement.Side.CLIENT));
        assertEquals(
                ResourceToolPlacement.Decision.REMOTE,
                ResourceToolPlacement.decide(
                        "openallay:resource_read", game, ResourceToolPlacement.Side.SERVER));
        assertEquals(
                ResourceToolPlacement.Decision.REMOTE,
                ResourceToolPlacement.decide(
                        "openallay:resource_read", world, ResourceToolPlacement.Side.CLIENT));
        assertEquals(
                ResourceToolPlacement.Decision.LOCAL,
                ResourceToolPlacement.decide(
                        "openallay:resource_read", world, ResourceToolPlacement.Side.SERVER));
    }

    @Test
    void rejectsOneBatchThatWouldCrossAuthorityOwners() {
        var mixed = JsonParser.parseString(
                "{\"plans\":[{\"roots\":[\"/game/options\",\"/world/dimension\"]}]}"
        ).getAsJsonObject();

        assertEquals(
                ResourceToolPlacement.Decision.CONFLICT,
                ResourceToolPlacement.decide(
                        "openallay:resource_query", mixed, ResourceToolPlacement.Side.CLIENT));
    }
}
