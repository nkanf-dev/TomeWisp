package dev.openallay.knowledge;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KnowledgeSnapshot(
        List<KnowledgeDocument> documents,
        Instant createdAt,
        List<EvidenceMetadata> evidence) {
    public KnowledgeSnapshot {
        documents = List.copyOf(documents);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) {
            evidence = List.of(emptyEvidence(createdAt));
        }
    }

    public KnowledgeSnapshot(List<KnowledgeDocument> documents, Instant createdAt) {
        this(
                documents,
                createdAt,
                documents.stream().map(KnowledgeDocument::evidence).distinct().toList());
    }

    public static KnowledgeSnapshot empty() {
        return new KnowledgeSnapshot(List.of(), Instant.EPOCH, List.of(emptyEvidence(Instant.EPOCH)));
    }

    private static EvidenceMetadata emptyEvidence(Instant createdAt) {
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.UNKNOWN,
                createdAt,
                "openallay:knowledge_registry",
                "openallay:empty_snapshot",
                "unknown",
                "unknown",
                Map.of("openallay:state", "not_loaded"));
    }
}
