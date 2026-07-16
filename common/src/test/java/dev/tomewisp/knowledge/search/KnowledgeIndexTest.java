package dev.tomewisp.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeKind;
import dev.tomewisp.knowledge.KnowledgeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class KnowledgeIndexTest {
    @Test
    void ranksExactIdThenAssociatedItemThenBodyAndSupportsChinese() {
        KnowledgeIndex index = new KnowledgeIndex(new KnowledgeSnapshot(List.of(
                doc("a", "minecraft:iron_ingot", "Other", "机械动力", Set.of()),
                doc("b", "other", "Iron", "body", Set.of("minecraft:iron_ingot")),
                doc("c", "other2", "Other", "minecraft:iron_ingot and 机械动力", Set.of())), Instant.now()));

        assertEquals("a", index.search("minecraft:iron_ingot", null).get(0).sourceId());
        assertEquals("b", index.search("minecraft:iron_ingot", null).get(1).sourceId());
        assertTrue(index.search("机械动力", null).size() >= 2);
        assertEquals(1, index.search("机械动力", 1).size());
    }

    @Test
    void stableTiesSortBySourceThenDocumentId() {
        KnowledgeIndex index = new KnowledgeIndex(new KnowledgeSnapshot(List.of(
                doc("z", "b", "same", "", Set.of()),
                doc("a", "c", "same", "", Set.of()),
                doc("a", "b", "same", "", Set.of())), Instant.now()));
        List<KnowledgeSearchResult> results = index.search("same", null);
        assertEquals(List.of("a:b", "a:c", "z:b"), results.stream()
                .map(result -> result.sourceId() + ":" + result.documentId()).toList());
    }

    private static KnowledgeDocument doc(
            String source, String id, String title, String body, Set<String> items) {
        return new KnowledgeDocument(source, id, KnowledgeKind.GUIDE_ENTRY, title, body, "example",
                items, Set.of(), null, true, "fixture:" + source);
    }
}
