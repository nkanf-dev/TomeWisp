package dev.tomewisp.knowledge;

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
        String provenance) {
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
