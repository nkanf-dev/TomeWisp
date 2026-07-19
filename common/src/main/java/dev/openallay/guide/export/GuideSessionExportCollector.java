package dev.openallay.guide.export;

import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.history.GuideHistoryAccess;
import dev.openallay.guide.history.GuideHistoryCursor;
import dev.openallay.guide.history.GuideHistoryException;
import dev.openallay.guide.history.GuideHistoryPage;
import dev.openallay.guide.history.GuideHistoryPageRequest;
import dev.openallay.guide.history.GuideHistoryScope;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Reads a complete durable session without mutating the GUI's history window. */
public final class GuideSessionExportCollector {
    private static final int PAGE_BATCH = 128;

    public record SequencedRequest(long sequence, GuideRequestSnapshot request) {
        public SequencedRequest {
            if (sequence < 0) throw new IllegalArgumentException("request sequence is negative");
            Objects.requireNonNull(request, "request");
        }
    }

    private final GuideHistoryScope scope;
    private final GuideHistoryAccess history;

    public GuideSessionExportCollector(GuideHistoryScope scope, GuideHistoryAccess history) {
        if ((scope == null) != (history == null)) {
            throw new IllegalArgumentException("history scope and access must be configured together");
        }
        this.scope = scope;
        this.history = history;
    }

    public CompletableFuture<GuideSessionExportSnapshot> collect(
            String sessionId,
            List<SequencedRequest> captured,
            Instant capturedAt) {
        requireSession(sessionId);
        captured = List.copyOf(captured);
        Objects.requireNonNull(capturedAt, "capturedAt");
        for (SequencedRequest value : captured) {
            if (!value.request().sessionId().equals(sessionId)) {
                throw new IllegalArgumentException("captured request belongs to another session");
            }
        }
        TreeMap<Long, GuideRequestSnapshot> ordered = new TreeMap<>();
        putAll(ordered, captured);
        if (history == null) {
            return CompletableFuture.completedFuture(snapshot(sessionId, ordered, capturedAt));
        }
        // The durable read can finish after the live request changes. Overlay the invocation-time
        // capture only after paging so the exported point in time wins by sequence and identity.
        List<SequencedRequest> live = captured;
        return loadEarlier(sessionId, null, ordered, new HashSet<>())
                .thenApply(ignored -> {
                    putAll(ordered, live);
                    return snapshot(sessionId, ordered, capturedAt);
                });
    }

    private CompletableFuture<Void> loadEarlier(
            String sessionId,
            GuideHistoryCursor before,
            TreeMap<Long, GuideRequestSnapshot> ordered,
            Set<GuideHistoryCursor> visited) {
        GuideHistoryPageRequest.Direction direction = before == null
                ? GuideHistoryPageRequest.Direction.NEWEST
                : GuideHistoryPageRequest.Direction.BEFORE;
        GuideHistoryPageRequest request = new GuideHistoryPageRequest(
                scope, sessionId, direction, before, PAGE_BATCH);
        CompletableFuture<GuideHistoryPage> loading;
        try {
            loading = Objects.requireNonNull(history.page(request), "history page future");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(exportFailure(failure));
        }
        return loading.handle((page, failure) -> {
                    if (failure != null) throw new java.util.concurrent.CompletionException(
                            exportFailure(failure));
                    validatePage(sessionId, before, page);
                    if (page.first() != null) {
                        long sequence = page.first().sequence();
                        for (GuideRequestSnapshot value : page.requests()) {
                            GuideRequestSnapshot previous = ordered.put(sequence++, value);
                            if (previous != null
                                    && !previous.requestId().equals(value.requestId())) {
                                throw new java.util.concurrent.CompletionException(exportFailure(
                                        new IllegalStateException("history sequence collision")));
                            }
                        }
                    }
                    return page;
                })
                .thenCompose(page -> {
                    if (!page.hasEarlier()) return CompletableFuture.completedFuture(null);
                    GuideHistoryCursor next = page.first();
                    if (next == null || !visited.add(next)
                            || before != null && next.sequence() >= before.sequence()) {
                        return CompletableFuture.failedFuture(exportFailure(
                                new IllegalStateException("history cursor did not progress")));
                    }
                    return loadEarlier(sessionId, next, ordered, visited);
                });
    }

    private static void validatePage(
            String sessionId, GuideHistoryCursor before, GuideHistoryPage page) {
        if (page == null || !page.sessionId().equals(sessionId)) {
            throw new java.util.concurrent.CompletionException(exportFailure(
                    new IllegalStateException("history page belongs to another session")));
        }
        if (before != null && page.last() != null && page.last().sequence() >= before.sequence()) {
            throw new java.util.concurrent.CompletionException(exportFailure(
                    new IllegalStateException("history page crossed its cursor")));
        }
    }

    private static void putAll(
            Map<Long, GuideRequestSnapshot> target, List<SequencedRequest> requests) {
        Set<UUID> identities = new HashSet<>();
        for (SequencedRequest value : requests) {
            if (!identities.add(value.request().requestId())) {
                throw new IllegalArgumentException("duplicate captured request identity");
            }
            GuideRequestSnapshot previous = target.put(value.sequence(), value.request());
            if (previous != null && !previous.requestId().equals(value.request().requestId())) {
                throw new IllegalArgumentException("captured history sequence collision");
            }
        }
    }

    private static GuideSessionExportSnapshot snapshot(
            String sessionId,
            TreeMap<Long, GuideRequestSnapshot> ordered,
            Instant capturedAt) {
        return new GuideSessionExportSnapshot(
                sessionId,
                ordered.values().stream().map(GuideSessionExportCollector::project).toList(),
                capturedAt);
    }

    private static GuideSessionExportSnapshot.Request project(GuideRequestSnapshot request) {
        List<GuideSessionExportSnapshot.Entry> timeline = request.timeline().stream()
                .map(GuideSessionExportCollector::projectEntry)
                .toList();
        return new GuideSessionExportSnapshot.Request(
                request.createdAt(), request.status(), request.userMessage(), timeline);
    }

    private static GuideSessionExportSnapshot.Entry projectEntry(GuideTimelineEntry entry) {
        return switch (entry) {
            case GuideTimelineEntry.Assistant assistant ->
                    new GuideSessionExportSnapshot.Entry.Assistant(
                            assistant.text(), assistant.streaming());
            case GuideTimelineEntry.Tool tool ->
                    new GuideSessionExportSnapshot.Entry.Tool(
                            tool.activity().toolId(), tool.activity().status());
        };
    }

    private static GuideHistoryException exportFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof GuideHistoryException historyFailure
                && historyFailure.code().equals("history_export_failed")
                ? historyFailure
                : new GuideHistoryException(
                        "history_export_failed",
                        "Unable to read the complete guide session for export",
                        cause);
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

    private static void requireSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid export session ID");
        }
    }
}
