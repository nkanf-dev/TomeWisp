package dev.openallay.knowledge.search;

import java.util.List;

/**
 * Narrow retrieval boundary for a detached snapshot.
 *
 * <p>The built-in implementation is deterministic and entirely local. A future optional reranker
 * can compose with that implementation, but exact identities and evidence remain properties of
 * the returned {@link KnowledgeSearchResult}, not of an external score.
 */
@FunctionalInterface
public interface KnowledgeRetriever {
    List<KnowledgeSearchResult> retrieve(String query);
}
