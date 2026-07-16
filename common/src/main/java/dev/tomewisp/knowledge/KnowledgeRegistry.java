package dev.tomewisp.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KnowledgeRegistry {
    private volatile KnowledgeSnapshot snapshot = KnowledgeSnapshot.empty();
    private volatile List<KnowledgeDiagnostic> diagnostics = List.of();

    public synchronized boolean reload(List<? extends KnowledgeSourceProvider> providers) {
        List<KnowledgeDocument> documents = new ArrayList<>();
        List<KnowledgeDiagnostic> nextDiagnostics = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try {
            for (KnowledgeSourceProvider provider : List.copyOf(providers)) {
                KnowledgeLoad load = provider.load();
                nextDiagnostics.addAll(load.diagnostics());
                for (KnowledgeDocument document : load.documents()) {
                    if (!document.sourceId().equals(provider.sourceId())
                            && !document.sourceId().startsWith(provider.sourceId() + ":")) {
                        throw new IllegalArgumentException("Provider " + provider.sourceId()
                                + " emitted document for " + document.sourceId());
                    }
                    if (!keys.add(document.key())) {
                        throw new IllegalArgumentException("Duplicate knowledge document " + document.key());
                    }
                    if (document.visible()) {
                        documents.add(document);
                    }
                }
            }
            documents.sort(java.util.Comparator.comparing(KnowledgeDocument::key));
            snapshot = new KnowledgeSnapshot(documents, Instant.now());
            diagnostics = List.copyOf(nextDiagnostics);
            return true;
        } catch (Exception failure) {
            String source = providers.isEmpty() ? "knowledge" : providers.getFirst().sourceId();
            diagnostics = List.of(new KnowledgeDiagnostic(
                    source,
                    "provider_failure",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    source));
            return false;
        }
    }

    public KnowledgeSnapshot snapshot() {
        return snapshot;
    }

    public List<KnowledgeDiagnostic> diagnostics() {
        return diagnostics;
    }
}
