package dev.tomewisp.knowledge.search;

import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.knowledge.KnowledgeKind;
import java.util.Set;

public record KnowledgeSearchResult(
        String sourceId,
        String documentId,
        KnowledgeKind kind,
        String title,
        String excerpt,
        int score,
        Set<String> matchedFields,
        String provenance,
        EvidenceMetadata evidence) {
    public KnowledgeSearchResult {
        matchedFields = Set.copyOf(matchedFields);
        java.util.Objects.requireNonNull(evidence, "evidence");
    }
}
