package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResourceNodeTest {
    @Test
    void sortsChildrenAndLinksAndRequiresEvidence() {
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.DETERMINISTIC_TEST,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "openallay:test",
                "openallay:test",
                "26.2",
                "test",
                Map.of());
        ResourceNode node = new ResourceNode(
                ResourcePath.parse("/item/minecraft/apple"),
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(Map.of("name", new ResourceValue.Scalar("Apple"))),
                List.of(
                        new ResourceEntry(ResourcePath.parse("/item/minecraft/apple/tags"), ResourceKind.LIST, "Tags"),
                        new ResourceEntry(ResourcePath.parse("/item/minecraft/apple/name"), ResourceKind.SCALAR, "Name")),
                List.of(
                        new ResourceLink("usage", ResourcePath.parse("/recipe/minecraft/pie"), "Pie"),
                        new ResourceLink("recipe", ResourcePath.parse("/recipe/minecraft/apple"), "Apple")),
                evidence,
                ResourcePresentation.none());

        assertEquals("/item/minecraft/apple/name", node.children().getFirst().path().toString());
        assertEquals("recipe", node.links().getFirst().relation());
        assertThrows(NullPointerException.class, () -> new ResourceNode(
                node.path(), node.kind(), node.truth(), List.of(), List.of(), null, node.presentation()));
    }
}
