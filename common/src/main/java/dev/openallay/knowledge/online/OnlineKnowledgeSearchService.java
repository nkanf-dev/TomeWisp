package dev.openallay.knowledge.online;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.net.HttpCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Concurrent, independently degrading search across fixed public documentation sources. */
public final class OnlineKnowledgeSearchService {
    private final List<OnlineKnowledgeSource> sources;

    public OnlineKnowledgeSearchService(List<? extends OnlineKnowledgeSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public CompletableFuture<OnlineKnowledgeSearch> search(
            String query,
            int limit,
            ToolInvocationContext context,
            HttpCancellation cancellation) {
        List<CompletableFuture<SourceOutcome>> pending = sources.stream()
                .map(source -> source.search(query, limit, cancellation)
                        .handle((hits, failure) -> failure == null
                                ? SourceOutcome.success(source, hits)
                                : SourceOutcome.failure(source, unwrap(failure))))
                .toList();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> combine(pending, context));
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
            EvidenceMetadata evidence = evidence(context, outcome.source());
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
            ToolInvocationContext context, OnlineKnowledgeSource source) {
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
                DataCompleteness.PARTIAL,
                context.capturedAt(),
                source.sourceId(),
                source.provenance(),
                gameVersion,
                loader,
                Map.of("openallay:scope", "public_search_excerpt"));
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
}
