package dev.openallay.knowledge.online;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.net.HttpCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

/** Concurrent, independently degrading search across fixed public documentation sources. */
public final class OnlineKnowledgeSearchService {
    private final List<OnlineKnowledgeSource> sources;
    private final Map<String, OnlineKnowledgeSource> sourcesById;

    public OnlineKnowledgeSearchService(List<? extends OnlineKnowledgeSource> sources) {
        this.sources = List.copyOf(sources);
        LinkedHashMap<String, OnlineKnowledgeSource> byId = new LinkedHashMap<>();
        for (OnlineKnowledgeSource source : this.sources) {
            OnlineKnowledgeSource previous = byId.put(source.sourceId(), source);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate online knowledge source: " + source.sourceId());
            }
        }
        this.sourcesById = Map.copyOf(byId);
    }

    public List<SourceDescriptor> sources() {
        return sources.stream().map(source -> new SourceDescriptor(
                source.sourceId(), source.provenance(), sourcePathSegment(source.sourceId()))).toList();
    }

    public CompletableFuture<OnlineKnowledgeSearch> search(
            String query,
            int limit,
            ToolInvocationContext context,
            HttpCancellation cancellation) {
        List<CompletableFuture<SourceOutcome>> pending = sources.stream()
                .map(source -> source.search(query, limit, cancellation)
                        .handle((hits, failure) -> {
                            if (cancellation.isCancelled()) {
                                throw new CompletionException(new CancellationException(
                                        "Online knowledge search cancelled"));
                            }
                            return failure == null
                                    ? SourceOutcome.success(source, hits)
                                    : SourceOutcome.failure(source, unwrap(failure));
                        }))
                .toList();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    if (cancellation.isCancelled()) {
                        throw new CancellationException("Online knowledge search cancelled");
                    }
                    return combine(pending, context);
                });
    }

    public CompletableFuture<OnlineKnowledgeDocument> read(
            String sourceId,
            String reference,
            dev.openallay.resource.vfs.ResourcePath path,
            ToolInvocationContext context,
            HttpCancellation cancellation) {
        OnlineKnowledgeSource source = sourcesById.get(sourceId);
        if (source == null) {
            return CompletableFuture.failedFuture(new OnlineKnowledgeException(
                    "online_source_unavailable", "The online knowledge source is unavailable"));
        }
        return source.read(reference, cancellation).thenApply(raw -> {
            if (cancellation.isCancelled()) {
                throw new CancellationException("Online knowledge document read cancelled");
            }
            return new OnlineKnowledgeDocument(
                    source.sourceId(),
                    path,
                    raw.title(),
                    raw.sections().stream().map(section -> new OnlineKnowledgeDocument.Section(
                            section.id(), section.heading(), section.text())).toList(),
                    raw.reference(),
                    evidence(context, source, DataCompleteness.PARTIAL, "public_document"));
        });
    }

    private static OnlineKnowledgeSearch combine(
            List<CompletableFuture<SourceOutcome>> pending,
            ToolInvocationContext context) {
        List<OnlineKnowledgeHit> hits = new ArrayList<>();
        List<OnlineKnowledgeDiagnostic> diagnostics = new ArrayList<>();
        for (CompletableFuture<SourceOutcome> future : pending) {
            SourceOutcome outcome = future.join();
            if (outcome.failure() != null) {
                Throwable failure = outcome.failure();
                String code = failure instanceof OnlineKnowledgeException online
                        ? online.code()
                        : "online_source_unavailable";
                diagnostics.add(new OnlineKnowledgeDiagnostic(
                        outcome.source().sourceId(), code, playerSafeMessage(code)));
                continue;
            }
            EvidenceMetadata evidence = evidence(
                    context, outcome.source(), DataCompleteness.PARTIAL, "public_search_excerpt");
            for (OnlineKnowledgeSource.RawHit hit : outcome.hits()) {
                hits.add(new OnlineKnowledgeHit(
                        outcome.source().sourceId(),
                        hit.title(),
                        hit.excerpt(),
                        hit.reference(),
                        evidence));
            }
        }
        return new OnlineKnowledgeSearch(hits, diagnostics);
    }

    private static EvidenceMetadata evidence(
            ToolInvocationContext context,
            OnlineKnowledgeSource source,
            DataCompleteness completeness,
            String scope) {
        String gameVersion = context.observableGameState()
                .map(snapshot -> snapshot.runtime().gameVersion())
                .orElseGet(() -> context.registries()
                        .map(snapshot -> snapshot.evidence().gameVersion())
                        .orElse("unknown"));
        String loader = context.observableGameState()
                .map(snapshot -> snapshot.runtime().loader())
                .orElseGet(() -> context.registries()
                        .map(snapshot -> snapshot.evidence().loader())
                        .orElse("unknown"));
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                completeness,
                context.capturedAt(),
                source.sourceId(),
                source.provenance(),
                gameVersion,
                loader,
                Map.of("openallay:scope", scope));
    }

    private static String playerSafeMessage(String code) {
        return switch (code) {
            case "online_http_status" -> "The public knowledge source returned an error";
            case "online_parse_failed" -> "The public knowledge source response could not be read";
            default -> "The public knowledge source is temporarily unavailable";
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record SourceOutcome(
            OnlineKnowledgeSource source,
            List<OnlineKnowledgeSource.RawHit> hits,
            Throwable failure) {
        private static SourceOutcome success(
                OnlineKnowledgeSource source, List<OnlineKnowledgeSource.RawHit> hits) {
            return new SourceOutcome(source, List.copyOf(hits), null);
        }

        private static SourceOutcome failure(OnlineKnowledgeSource source, Throwable failure) {
            return new SourceOutcome(source, List.of(), failure);
        }
    }

    public record SourceDescriptor(String sourceId, String provenance, String pathSegment) {
        public SourceDescriptor {
            if (sourceId == null || sourceId.isBlank() || provenance == null || provenance.isBlank()
                    || pathSegment == null || pathSegment.isBlank()) {
                throw new IllegalArgumentException("invalid online knowledge source descriptor");
            }
        }
    }

    static String sourcePathSegment(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        int separator = sourceId.indexOf(':');
        String value = separator < 0 ? sourceId : sourceId.substring(separator + 1);
        if (!value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Online knowledge source ID has no safe path segment");
        }
        return value;
    }
}
