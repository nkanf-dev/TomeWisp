package dev.tomewisp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class KnowledgeRegistryTest {
    @Test
    void failedProviderDoesNotReplaceLastGoodSnapshot() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        assertTrue(registry.reload(List.of(provider("guide", document("first")))));
        assertFalse(registry.reload(List.of(new KnowledgeSourceProvider() {
            @Override public String sourceId() { return "broken"; }
            @Override public KnowledgeLoad load() { throw new IllegalStateException("no data"); }
        })));

        assertEquals("first", registry.snapshot().documents().getFirst().title());
        assertEquals("provider_failure", registry.diagnostics().getFirst().code());
    }

    @Test
    void rejectsDuplicateIdentityAndExcludesInvisibleDocuments() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        KnowledgeDocument visible = document("visible");
        KnowledgeDocument hidden = new KnowledgeDocument(
                "guide", "hidden", KnowledgeKind.QUEST, "hidden", "secret", "example",
                Set.of(), Set.of(), null, false, "fixture");
        assertTrue(registry.reload(List.of(provider("guide", visible, hidden))));
        assertEquals(1, registry.snapshot().documents().size());
        assertFalse(registry.reload(List.of(provider("guide", visible, visible))));
        assertEquals(1, registry.snapshot().documents().size());
    }

    private static KnowledgeSourceProvider provider(String id, KnowledgeDocument... documents) {
        return new KnowledgeSourceProvider() {
            @Override public String sourceId() { return id; }
            @Override public KnowledgeLoad load() { return KnowledgeLoad.of(List.of(documents)); }
        };
    }

    private static KnowledgeDocument document(String title) {
        return new KnowledgeDocument(
                "guide", "entry", KnowledgeKind.GUIDE_ENTRY, title, "body", "example",
                Set.of("minecraft:iron_ingot"), Set.of(), null, true, "fixture");
    }
}
