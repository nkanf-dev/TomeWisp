package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import org.junit.jupiter.api.Test;

final class GameStateResourceMountTest {
    @Test
    void exposesRuntimeDiagnosticsAndAllowedReadOnlyQueriesButNoWorldScanRoot() {
        var snapshot = new GameStateResourceMount(GroundedTestFixtures::observableGameState).snapshot();
        var coordinate = snapshot.nodes().get(
                ResourcePath.parse("/game/diagnostics/position/coordinates"));
        ResourceValue.RecordValue time = (ResourceValue.RecordValue) snapshot.nodes()
                .get(ResourcePath.parse("/game/queries/time")).truth();

        assertEquals(new ResourceValue.Scalar("1 64 2"), coordinate.truth());
        assertEquals(ResourcePresentation.Kind.DIAGNOSTICS, coordinate.presentation().kind());
        assertEquals(new ResourceValue.Scalar(true), time.fields().get("authoritative"));
        assertTrue(snapshot.nodes().keySet().stream().noneMatch(
                path -> path.toString().startsWith("/game/world/")));
    }
}
