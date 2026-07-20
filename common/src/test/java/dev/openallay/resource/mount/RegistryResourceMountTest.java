package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.resource.vfs.ResourcePath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RegistryResourceMountTest {
    @Test
    void exposesUnknownPropertiesAndRuntimeSchemaWithoutWhitelist() {
        RegistryEntrySnapshot entry = new RegistryEntrySnapshot(
                "othermod:charged_blade", "item", "Charged Blade", "othermod", "minecraft:registry",
                List.of("blade"), Set.of("othermod:weapons"), Set.of("minecraft:max_damage"),
                Map.of("othermod:charge_capacity", new JsonPrimitive(9001)));
        RegistryResourceMount mount = new RegistryResourceMount("item",
                () -> new RegistrySnapshot(evidence(), List.of(entry)));

        var snapshot = mount.snapshot();
        assertTrue(snapshot.nodes().containsKey(ResourcePath.parse("/item/othermod/charged_blade")));
        assertTrue(snapshot.nodes().containsKey(ResourcePath.parse("/item/othermod/charged_blade/@schema")));
        assertEquals("registry-item-1", snapshot.generationId());
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.EPOCH, "openallay:test", "openallay:test", "26.2", "test", Map.of());
    }
}
