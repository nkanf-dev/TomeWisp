package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import org.junit.jupiter.api.Test;

final class PlayerResourceMountTest {
    @Test
    void exposesOnlyDetachedPlayerVisibleProfileAndInventory() {
        var snapshot = new PlayerResourceMount(GroundedTestFixtures::player).snapshot();
        ResourceValue.RecordValue profile = (ResourceValue.RecordValue) snapshot.nodes()
                .get(ResourcePath.parse("/player/profile")).truth();
        var slot = snapshot.nodes().get(ResourcePath.parse("/player/inventory/slot/0"));

        assertEquals(new ResourceValue.Scalar("minecraft:overworld"), profile.fields().get("dimension"));
        assertEquals(GroundedTestFixtures.playerEvidence(), slot.evidence());
        assertTrue(slot.links().stream().anyMatch(link ->
                link.target().equals(ResourcePath.parse("/item/minecraft/iron_ingot"))));
    }
}
