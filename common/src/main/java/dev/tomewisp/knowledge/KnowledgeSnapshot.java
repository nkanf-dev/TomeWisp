package dev.tomewisp.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeSnapshot(List<KnowledgeDocument> documents, Instant createdAt) {
    public KnowledgeSnapshot {
        documents = List.copyOf(documents);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static KnowledgeSnapshot empty() {
        return new KnowledgeSnapshot(List.of(), Instant.EPOCH);
    }
}
