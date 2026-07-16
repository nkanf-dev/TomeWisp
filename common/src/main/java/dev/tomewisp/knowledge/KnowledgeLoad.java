package dev.tomewisp.knowledge;

import java.util.List;

public record KnowledgeLoad(
        List<KnowledgeDocument> documents, List<KnowledgeDiagnostic> diagnostics) {
    public KnowledgeLoad {
        documents = List.copyOf(documents);
        diagnostics = List.copyOf(diagnostics);
    }

    public static KnowledgeLoad of(List<KnowledgeDocument> documents) {
        return new KnowledgeLoad(documents, List.of());
    }
}
