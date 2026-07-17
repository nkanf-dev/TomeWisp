package dev.tomewisp.knowledge;

import dev.tomewisp.context.EvidenceMetadata;
import java.util.List;

public record KnowledgeLoad(
        List<KnowledgeDocument> documents,
        List<KnowledgeDiagnostic> diagnostics,
        List<EvidenceMetadata> evidence) {
    public KnowledgeLoad {
        documents = List.copyOf(documents);
        diagnostics = List.copyOf(diagnostics);
        evidence = List.copyOf(evidence);
    }

    public KnowledgeLoad(
            List<KnowledgeDocument> documents, List<KnowledgeDiagnostic> diagnostics) {
        this(
                documents,
                diagnostics,
                documents.stream().map(KnowledgeDocument::evidence).distinct().toList());
    }

    public static KnowledgeLoad of(List<KnowledgeDocument> documents) {
        return new KnowledgeLoad(
                documents,
                List.of(),
                documents.stream().map(KnowledgeDocument::evidence).distinct().toList());
    }
}
