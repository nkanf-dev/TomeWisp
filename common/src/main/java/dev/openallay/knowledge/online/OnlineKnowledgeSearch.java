package dev.openallay.knowledge.online;

import java.util.List;

public record OnlineKnowledgeSearch(
        List<OnlineKnowledgeHit> hits,
        List<OnlineKnowledgeDiagnostic> diagnostics) {
    public OnlineKnowledgeSearch {
        hits = List.copyOf(hits);
        diagnostics = List.copyOf(diagnostics);
    }
}
