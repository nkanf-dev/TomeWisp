package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.integration.patchouli.PatchouliMultiblock;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.knowledge.KnowledgeDocument;
import dev.openallay.knowledge.KnowledgeKind;
import dev.openallay.knowledge.KnowledgeSnapshot;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class KnowledgeResourceMountTest {
    @Test
    void exposesReferencedPatchouliStructureAsLinkedQueryableResource() {
        String structureRef = "patchouli:machines/press#page-2";
        KnowledgeDocument document = new KnowledgeDocument(
                "patchouli",
                "machines/press",
                KnowledgeKind.STRUCTURE,
                "Mechanical Press",
                "Build the frame before installing the press.",
                "example",
                Set.of("example:press"),
                Set.of(),
                structureRef,
                true,
                "fixture:guide",
                GroundedTestFixtures.playerEvidence());
        KnowledgeSnapshot knowledge = new KnowledgeSnapshot(
                List.of(document),
                Instant.EPOCH,
                List.of(GroundedTestFixtures.playerEvidence()));
        PatchouliMultiblockStore structures = new PatchouliMultiblockStore();
        structures.replace(Map.of(
                structureRef,
                new PatchouliMultiblock(
                        structureRef,
                        List.of(
                                new PatchouliMultiblock.Block(0, 0, 0, "minecraft:stone"),
                                new PatchouliMultiblock.Block(1, 0, 0, "example:casing")),
                        "fixture:structure",
                        GroundedTestFixtures.playerEvidence())));

        var snapshot = new KnowledgeResourceMount(() -> knowledge, structures).snapshot();
        ResourcePath documentPath =
                ResourcePath.of("knowledge", "patchouli", "machines/press");
        ResourcePath structurePath = documentPath.child("structure");

        assertTrue(snapshot.nodes().get(documentPath).links().stream()
                .anyMatch(link -> link.relation().equals("structure")
                        && link.target().equals(structurePath)));
        ResourceValue.RecordValue structure =
                (ResourceValue.RecordValue) snapshot.nodes().get(structurePath).truth();
        assertEquals("2", ((ResourceValue.Scalar) structure.fields().get("block_count"))
                .value().toString());
        ResourceValue.ListValue blocks =
                (ResourceValue.ListValue) structure.fields().get("blocks");
        assertEquals(2, blocks.values().size());
        assertEquals(
                "example:casing",
                ((ResourceValue.Scalar) ((ResourceValue.RecordValue) blocks.values().get(1))
                        .fields().get("state")).value());
        assertTrue(snapshot.nodes().containsKey(structurePath.child("@schema")));
    }
}
