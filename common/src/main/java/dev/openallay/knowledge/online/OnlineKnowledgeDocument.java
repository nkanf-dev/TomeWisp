package dev.openallay.knowledge.online;

import dev.openallay.context.EvidenceMetadata;

public record OnlineKnowledgeDocument(
        String sourceId,
        String title,
        String body,
        String reference,
        EvidenceMetadata evidence) {
    public OnlineKnowledgeDocument {
        if (sourceId == null || sourceId.isBlank() || title == null || title.isBlank()
                || body == null || body.isBlank() || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("online document fields are required");
        }
        java.util.Objects.requireNonNull(evidence, "evidence");
    }
}
