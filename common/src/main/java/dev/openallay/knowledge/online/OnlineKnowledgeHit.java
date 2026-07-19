package dev.openallay.knowledge.online;

import dev.openallay.context.EvidenceMetadata;

/** One partial public-documentation search hit from a fixed registered origin. */
public record OnlineKnowledgeHit(
        String sourceId,
        String title,
        String excerpt,
        String reference,
        EvidenceMetadata evidence) {
    public OnlineKnowledgeHit {
        if (sourceId == null || sourceId.isBlank()
                || title == null || title.isBlank()
                || excerpt == null
                || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("invalid online knowledge hit");
        }
        java.util.Objects.requireNonNull(evidence, "evidence");
    }
}
