package dev.openallay.knowledge.online;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.vfs.ResourcePath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps discovered remote references private to one request and exposes opaque VFS paths. */
public final class OnlineKnowledgeRequestAccess implements OnlineKnowledgeAccess {
    private final OnlineKnowledgeSearchService service;
    private final ToolInvocationContext context;
    private final Map<ResourcePath, Discovered> discovered = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OnlineKnowledgeRequestAccess(
            OnlineKnowledgeSearchService service, ToolInvocationContext context) {
        this.service = Objects.requireNonNull(service, "service");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public CompletableFuture<OnlineKnowledgeResourceSearch> search(
            String query, CancellationSignal cancellation) {
        ensureOpen();
        if (query == null || query.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("query is required"));
        }
        return service.search(query, Integer.MAX_VALUE, context, cancellation).thenApply(search -> {
            ensureOpen();
            ArrayList<OnlineKnowledgeResourceHit> hits = new ArrayList<>();
            for (OnlineKnowledgeHit hit : search.hits()) {
                String source = OnlineKnowledgeSearchService.sourcePathSegment(hit.sourceId());
                ResourcePath path = ResourcePath.of(
                        "knowledge", "online", source, digest(hit.sourceId() + "\n" + hit.reference()));
                Discovered candidate = new Discovered(hit.sourceId(), hit.reference());
                Discovered previous = discovered.putIfAbsent(path, candidate);
                if (previous != null && !previous.equals(candidate)) {
                    throw new IllegalStateException("Online knowledge reference digest collision");
                }
                hits.add(new OnlineKnowledgeResourceHit(
                        hit.sourceId(), hit.title(), hit.excerpt(), path, hit.evidence()));
            }
            return new OnlineKnowledgeResourceSearch(hits, search.diagnostics());
        });
    }

    @Override
    public CompletableFuture<OnlineKnowledgeDocument> read(
            ResourcePath path, CancellationSignal cancellation) {
        ensureOpen();
        Discovered reference = discovered.get(Objects.requireNonNull(path, "path"));
        if (reference == null) {
            return CompletableFuture.failedFuture(new OnlineKnowledgeException(
                    "online_document_not_discovered",
                    "Search this fixed knowledge source before reading the document"));
        }
        return service.read(reference.sourceId(), reference.reference(), path, context, cancellation);
    }

    @Override
    public void close() {
        closed.set(true);
        discovered.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Online knowledge request has ended");
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record Discovered(String sourceId, String reference) {}
}
