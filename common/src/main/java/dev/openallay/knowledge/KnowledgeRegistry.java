package dev.openallay.knowledge;

import dev.openallay.knowledge.search.KnowledgeIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KnowledgeRegistry {
    private volatile PublishedKnowledge published = published(KnowledgeSnapshot.empty());
    private volatile List<KnowledgeDiagnostic> diagnostics = List.of();
    private List<KnowledgeSourceProvider> primaryProviders = List.of();
    private List<KnowledgeSourceProvider> supplementalProviders = List.of();
    private Set<String> disabledPrimaryProviderIds = Set.of();

    public synchronized boolean reload(List<? extends KnowledgeSourceProvider> providers) {
        List<KnowledgeSourceProvider> candidate = List.copyOf(providers);
        if (!load(enabledPrimary(candidate, disabledPrimaryProviderIds), supplementalProviders)) {
            return false;
        }
        primaryProviders = candidate;
        return true;
    }

    /**
     * Replaces settings-owned providers without losing the current resource-owned providers. A
     * rejected candidate retains both the prior provider set and the last valid snapshot.
     */
    public synchronized boolean replaceSupplementalProviders(
            List<? extends KnowledgeSourceProvider> providers) {
        return replaceProviderConfiguration(disabledPrimaryProviderIds, providers);
    }

    /** Applies Tool-owned enablement to resource/integration providers by their stable source ID. */
    public synchronized boolean replaceDisabledPrimaryProviders(Set<String> sourceIds) {
        return replaceProviderConfiguration(sourceIds, supplementalProviders);
    }

    /** Atomically validates and publishes both Tool-owned provider selections. */
    public synchronized boolean replaceProviderConfiguration(
            Set<String> sourceIds,
            List<? extends KnowledgeSourceProvider> supplemental) {
        Set<String> candidate = Set.copyOf(sourceIds);
        if (candidate.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Disabled knowledge source IDs must not be blank");
        }
        List<KnowledgeSourceProvider> supplementalCandidate = List.copyOf(supplemental);
        if (!load(enabledPrimary(primaryProviders, candidate), supplementalCandidate)) {
            return false;
        }
        disabledPrimaryProviderIds = candidate;
        supplementalProviders = supplementalCandidate;
        return true;
    }

    private static List<KnowledgeSourceProvider> enabledPrimary(
            List<KnowledgeSourceProvider> providers, Set<String> disabled) {
        return providers.stream()
                .filter(provider -> !disabled.contains(provider.sourceId()))
                .toList();
    }

    private boolean load(
            List<? extends KnowledgeSourceProvider> primary,
            List<? extends KnowledgeSourceProvider> supplemental) {
        List<KnowledgeSourceProvider> providers = new ArrayList<>(primary.size() + supplemental.size());
        providers.addAll(primary);
        providers.addAll(supplemental);
        List<KnowledgeDocument> documents = new ArrayList<>();
        List<KnowledgeDiagnostic> nextDiagnostics = new ArrayList<>();
        List<dev.openallay.context.EvidenceMetadata> evidence = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        String failureCode = "provider_failure";
        try {
            for (KnowledgeSourceProvider provider : providers) {
                KnowledgeLoad load = provider.load();
                nextDiagnostics.addAll(load.diagnostics());
                evidence.addAll(load.evidence());
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
            KnowledgeSnapshot nextSnapshot = new KnowledgeSnapshot(
                    documents, Instant.now(), evidence.stream().distinct().toList());
            failureCode = "index_failure";
            PublishedKnowledge next = published(nextSnapshot);
            published = next;
            diagnostics = List.copyOf(nextDiagnostics);
            return true;
        } catch (Exception failure) {
            String source = providers.isEmpty() ? "knowledge" : providers.getFirst().sourceId();
            diagnostics = List.of(new KnowledgeDiagnostic(
                    source,
                    failureCode,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    source));
            return false;
        }
    }

    public KnowledgeSnapshot snapshot() {
        return published.snapshot();
    }

    public KnowledgeSearch search(String query, Integer limit) {
        PublishedKnowledge current = published;
        return new KnowledgeSearch(
                current.index().search(query, limit),
                current.snapshot().evidence());
    }

    public List<KnowledgeDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static PublishedKnowledge published(KnowledgeSnapshot snapshot) {
        return new PublishedKnowledge(snapshot, new KnowledgeIndex(snapshot));
    }

    private record PublishedKnowledge(KnowledgeSnapshot snapshot, KnowledgeIndex index) {}
}
