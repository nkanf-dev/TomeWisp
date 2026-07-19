package dev.openallay.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EvidenceMetadataTest {
    @Test
    void validatesAndCopiesEvidenceDetails() {
        Map<String, String> details = new HashMap<>();
        details.put("openallay:scope", "active_connection");
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "minecraft:client_recipe_book",
                "minecraft:client_recipe_display",
                "26.2",
                "fabric",
                details);

        details.clear();

        assertEquals("active_connection", evidence.details().get("openallay:scope"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> evidence.details().put("example:key", "value"));
    }

    @Test
    void rejectsInvalidIdentityAndDetails() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.UNKNOWN,
                Instant.EPOCH,
                "client recipe book",
                "minecraft:client_recipe_display",
                "26.2",
                "fabric",
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.UNKNOWN,
                Instant.EPOCH,
                "minecraft:client_recipe_book",
                "minecraft:client_recipe_display",
                "26.2",
                "fabric",
                Map.of("not namespaced", "value")));
    }
}
