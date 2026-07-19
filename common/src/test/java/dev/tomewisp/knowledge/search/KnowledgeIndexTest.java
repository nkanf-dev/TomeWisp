package dev.tomewisp.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void exactIdentityCannotBeDisplacedByRepeatedBodyTerms() {
        String repeated = ("minecraft:iron_ingot ").repeat(2_000);
        KnowledgeIndex index = new KnowledgeIndex(new KnowledgeSnapshot(List.of(
                doc("exact", "minecraft:iron_ingot", "Identity", "", Set.of()),
                doc("body", "other", "Incidental", repeated, Set.of())), Instant.EPOCH));

        List<KnowledgeSearchResult> results = index.search("minecraft:iron_ingot", null);

        assertEquals("exact", results.getFirst().sourceId());
        assertTrue(results.getFirst().matchedFields().contains("documentId"));
    }

    @Test
    void resourceAliasesAndMetadataAreWeightedFields() {
        KnowledgeDocument alias = doc(
                "alias", "entry", "Drink", "", Set.of("farmersdelight:apple_cider"));
        KnowledgeDocument metadata = new KnowledgeDocument(
                "guide", "machine", KnowledgeKind.GUIDE_ENTRY, "Machine", "", "create",
                Set.of(), Set.of(), null, true, "fixture:metadata");
        KnowledgeIndex index = new KnowledgeIndex(
                new KnowledgeSnapshot(List.of(alias, metadata), Instant.EPOCH));

        KnowledgeSearchResult aliasResult = index.search("apple cider", null).getFirst();
        KnowledgeSearchResult metadataResult = index.search("create", null).getFirst();

        assertEquals("alias", aliasResult.sourceId());
        assertTrue(aliasResult.matchedFields().contains("aliases"));
        assertEquals("machine", metadataResult.documentId());
        assertTrue(metadataResult.matchedFields().contains("metadata"));
    }

    @Test
    void headingAwareResultsExposeStableSectionReferences() {
        KnowledgeDocument first = doc(
                "guide", "machine", "Machine", "# Setup\nUse a wrench.\n# Power\nNeeds stress units.", Set.of());
        KnowledgeDocument changedElsewhere = doc(
                "guide", "machine", "Machine", "# Introduction\nWelcome.\n# Setup\nUse a wrench.\n# Power\nNeeds stress units.", Set.of());

        KnowledgeSearchResult before = new KnowledgeIndex(
                        new KnowledgeSnapshot(List.of(first), Instant.EPOCH))
                .search("stress units", null)
                .getFirst();
        KnowledgeSearchResult after = new KnowledgeIndex(
                        new KnowledgeSnapshot(List.of(changedElsewhere), Instant.EPOCH))
                .search("stress units", null)
                .getFirst();

        assertEquals("power", before.sectionId());
        assertEquals("Power", before.sectionTitle());
        assertEquals(before.sectionReference(), after.sectionReference());
        assertEquals("tomewisp-knowledge:guide/machine#power", before.sectionReference());
    }

    @Test
    void markdownHeadingsInsideCodeFencesDoNotCreateSections() {
        KnowledgeDocument document = doc(
                "guide", "commands", "Commands",
                "# Usage\n```text\n# not-a-heading\ncommand output\n```", Set.of());

        KnowledgeSearchResult result = new KnowledgeIndex(
                        new KnowledgeSnapshot(List.of(document), Instant.EPOCH))
                .search("command output", null)
                .getFirst();

        assertEquals("usage", result.sectionId());
    }

    @Test
    void bm25LengthNormalizationPrefersFocusedSection() {
        KnowledgeIndex index = new KnowledgeIndex(new KnowledgeSnapshot(List.of(
                doc("focused", "focused", "Notes", "flux capacitor", Set.of()),
                doc("verbose", "verbose", "Notes", "flux capacitor " + "unrelated ".repeat(500), Set.of())),
                Instant.EPOCH));

        assertEquals("focused", index.search("flux capacitor", null).getFirst().sourceId());
    }

    @Test
    void tokenizerNormalizesFullWidthTextAndAddsCjkBigrams() {
        KnowledgeTokenizer tokenizer = new KnowledgeTokenizer();

        assertEquals("create:shaft", tokenizer.normalize("ＣＲＥＡＴＥ：ＳＨＡＦＴ"));
        assertTrue(tokenizer.tokenize("机械动力").containsAll(List.of("机械动力", "机械", "动力")));
        assertTrue(tokenizer.tokenize("farmersdelight:apple_cider")
                .contains("farmersdelight:apple_cider"));
    }

    @Test
    void validatesLimitBeforeDelegatingToRetriever() {
        KnowledgeIndex index = new KnowledgeIndex(KnowledgeSnapshot.empty());

        assertThrows(IllegalArgumentException.class, () -> index.search("query", 0));
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
