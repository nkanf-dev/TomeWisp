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

    @Test
    void supplementalProvidersSurvivePrimaryReloadAndRollbackIndependently() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        KnowledgeDocument primary = document("guide", "primary", "Primary");
        KnowledgeDocument supplemental = document("notes", "local", "Local");

        assertTrue(registry.reload(List.of(provider("guide", primary))));
        assertTrue(registry.replaceSupplementalProviders(List.of(provider("notes", supplemental))));
        assertEquals(List.of("Primary", "Local"), registry.snapshot().documents().stream()
                .map(KnowledgeDocument::title)
                .toList());

        KnowledgeDocument refreshed = document("guide", "refreshed", "Refreshed");
        assertTrue(registry.reload(List.of(provider("guide", refreshed))));
        assertEquals(List.of("Refreshed", "Local"), registry.snapshot().documents().stream()
                .map(KnowledgeDocument::title)
                .toList());

        assertFalse(registry.replaceSupplementalProviders(List.of(new KnowledgeSourceProvider() {
            @Override public String sourceId() { return "broken"; }
            @Override public KnowledgeLoad load() { throw new IllegalStateException("no data"); }
        })));
        assertEquals(List.of("Refreshed", "Local"), registry.snapshot().documents().stream()
                .map(KnowledgeDocument::title)
                .toList());
    }

    @Test
    void toolOwnedPrimaryProviderPolicySurvivesResourceReload() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        assertTrue(registry.reload(List.of(
                provider("patchouli", document("patchouli", "entry", "Book")),
                provider("ftbquests", document("ftbquests", "quest", "Quest")))));

        assertTrue(registry.replaceDisabledPrimaryProviders(Set.of("patchouli")));
        assertEquals(List.of("Quest"), registry.snapshot().documents().stream()
                .map(KnowledgeDocument::title)
                .toList());

        assertTrue(registry.reload(List.of(
                provider("patchouli", document("patchouli", "new", "New Book")),
                provider("ftbquests", document("ftbquests", "new", "New Quest")))));
        assertEquals(List.of("New Quest"), registry.snapshot().documents().stream()
                .map(KnowledgeDocument::title)
                .toList());
    }

    private static KnowledgeSourceProvider provider(String id, KnowledgeDocument... documents) {
        return new KnowledgeSourceProvider() {
            @Override public String sourceId() { return id; }
            @Override public KnowledgeLoad load() { return KnowledgeLoad.of(List.of(documents)); }
        };
    }

    private static KnowledgeDocument document(String title) {
        return document("guide", "entry", title);
    }

    private static KnowledgeDocument document(String source, String id, String title) {
        return new KnowledgeDocument(
                source, id, KnowledgeKind.GUIDE_ENTRY, title, "body", "example",
                Set.of("minecraft:iron_ingot"), Set.of(), null, true, "fixture");
    }
}
