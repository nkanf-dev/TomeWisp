package dev.openallay.knowledge.online;

import java.util.List;

public record OnlineKnowledgeResourceSearch(
        List<OnlineKnowledgeResourceHit> hits,
        List<OnlineKnowledgeDiagnostic> diagnostics) {
    public OnlineKnowledgeResourceSearch {
        hits = List.copyOf(hits);
        diagnostics = List.copyOf(diagnostics);
    }
}
