package dev.openallay.knowledge;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record KnowledgeDocument(
        String sourceId,
        String documentId,
        KnowledgeKind kind,
        String title,
        String body,
        String namespace,
        Set<String> itemIds,
        Set<String> recipeIds,
        String structureRef,
        boolean visible,
        String provenance,
        EvidenceMetadata evidence) {
    public KnowledgeDocument {
        require(sourceId, "sourceId");
        require(documentId, "documentId");
        java.util.Objects.requireNonNull(kind, "kind");
        require(title, "title");
        body = body == null ? "" : body;
        namespace = namespace == null ? "" : namespace;
        itemIds = Set.copyOf(itemIds);
        recipeIds = Set.copyOf(recipeIds);
        require(provenance, "provenance");
        java.util.Objects.requireNonNull(evidence, "evidence");
    }

    public KnowledgeDocument(
            String sourceId,
            String documentId,
            KnowledgeKind kind,
            String title,
            String body,
            String namespace,
            Set<String> itemIds,
            Set<String> recipeIds,
            String structureRef,
            boolean visible,
            String provenance) {
        this(
                sourceId,
                documentId,
                kind,
                title,
                body,
                namespace,
                itemIds,
                recipeIds,
                structureRef,
                visible,
                provenance,
                new EvidenceMetadata(
                        DataAuthority.DETERMINISTIC_TEST,
                        DataCompleteness.COMPLETE,
                        Instant.EPOCH,
                        "openallay:test_fixture",
                        "openallay:knowledge_fixture",
                        "test",
                        "common-test",
                        Map.of("openallay:fixture_provenance", provenance)));
    }

    public String key() {
        return sourceId + ":" + documentId;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
