package dev.openallay.knowledge.search;

import dev.openallay.knowledge.KnowledgeSnapshot;
import java.util.List;
import java.util.Objects;

/** Immutable search facade for one detached knowledge snapshot. */
public final class KnowledgeIndex {
    private final KnowledgeRetriever retriever;

    public KnowledgeIndex(KnowledgeSnapshot snapshot) {
        this(snapshot, new KnowledgeTokenizer());
    }

    public KnowledgeIndex(KnowledgeSnapshot snapshot, KnowledgeTokenizer tokenizer) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.retriever = new DeterministicKnowledgeRetriever(snapshot, tokenizer);
    }

    public KnowledgeIndex(KnowledgeRetriever retriever) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    public List<KnowledgeSearchResult> search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive when present");
        }
        List<KnowledgeSearchResult> results = List.copyOf(retriever.retrieve(query));
        return limit == null || limit >= results.size()
                ? results
                : List.copyOf(results.subList(0, limit));
    }
}
