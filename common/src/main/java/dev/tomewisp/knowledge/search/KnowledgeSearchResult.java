package dev.tomewisp.knowledge.search;

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
        String provenance) {
    public KnowledgeSearchResult {
        matchedFields = Set.copyOf(matchedFields);
    }
}
