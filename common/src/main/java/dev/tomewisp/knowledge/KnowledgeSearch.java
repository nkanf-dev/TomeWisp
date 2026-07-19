package dev.tomewisp.knowledge;

import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.knowledge.search.KnowledgeSearchResult;
import java.util.List;

/** Search results and evidence captured from one atomically published knowledge generation. */
public record KnowledgeSearch(
        List<KnowledgeSearchResult> results,
        List<EvidenceMetadata> evidence) {
    public KnowledgeSearch {
        results = List.copyOf(results);
        evidence = List.copyOf(evidence);
    }
}
