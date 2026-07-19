package dev.tomewisp.guide.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryCommit;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryPage;
import dev.tomewisp.guide.history.GuideHistoryPageRequest;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.model.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class GuideSessionExportCollectorTest {
    private static final UUID ACTOR = UUID.fromString("b6c595a7-9e33-4ad2-af63-126503472b23");
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            ACTOR, GuideHistoryScope.Kind.SINGLEPLAYER, "world");
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    @Test
    void readsEveryEarlierPageAndOverlaysTheInvocationTimeLiveRequest() {
        GuideRequestSnapshot durableFour = request(4, "durable-four", true);
        GuideRequestSnapshot liveFour = request(4, "live-four", false);
        PagingHistory history = new PagingHistory(List.of(
                page(List.of(request(3, "three", true), durableFour), 3, 4, true),
                page(List.of(request(1, "one", true), request(2, "two", true)), 1, 2, false)));
        GuideSessionExportCollector collector = new GuideSessionExportCollector(SCOPE, history);

        GuideSessionExportSnapshot export = collector.collect(
                "main",
                List.of(new GuideSessionExportCollector.SequencedRequest(4, liveFour)),
                NOW).join();

        assertEquals(List.of("one", "two", "three", "live-four"), export.requests().stream()
                .map(GuideSessionExportSnapshot.Request::userMessage).toList());
        assertEquals(2, history.requests.size());
        assertEquals(GuideHistoryPageRequest.Direction.NEWEST,
                history.requests.get(0).direction());
        assertEquals(GuideHistoryPageRequest.Direction.BEFORE,
                history.requests.get(1).direction());
    }

    @Test
    void historyFailureIsMappedToOneClosedExportFailure() {
        GuideHistoryAccess history = new PagingHistory(List.of()) {
            @Override public CompletableFuture<GuideHistoryPage> page(GuideHistoryPageRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("database path"));
            }
        };
        GuideSessionExportCollector collector = new GuideSessionExportCollector(SCOPE, history);

        Throwable failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> collector.collect("main", List.of(), NOW).join()).getCause();

        dev.tomewisp.guide.history.GuideHistoryException mapped = assertInstanceOf(
                dev.tomewisp.guide.history.GuideHistoryException.class, failure);
        assertEquals("history_export_failed", mapped.code());
        assertTrue(mapped.getMessage().contains("complete guide session"));
    }

    @Test
    void nonProgressingEarlierCursorFailsInsteadOfLooping() {
        GuideHistoryPage newest = page(List.of(request(3, "three", true)), 3, 3, true);
        GuideHistoryPage repeated = page(List.of(request(3, "three", true)), 3, 3, true);
        GuideSessionExportCollector collector = new GuideSessionExportCollector(
                SCOPE, new PagingHistory(List.of(newest, repeated)));

        Throwable failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> collector.collect("main", List.of(), NOW).join()).getCause();

        assertEquals("history_export_failed",
                assertInstanceOf(
                        dev.tomewisp.guide.history.GuideHistoryException.class,
                        failure).code());
    }

    private static GuideHistoryPage page(
            List<GuideRequestSnapshot> requests,
            long first,
            long last,
            boolean earlier) {
        return new GuideHistoryPage(
                "main",
                requests,
                new dev.tomewisp.guide.history.GuideHistoryCursor(
                        first, requests.getFirst().requestId()),
                new dev.tomewisp.guide.history.GuideHistoryCursor(
                        last, requests.getLast().requestId()),
                earlier,
                false);
    }

    private static GuideRequestSnapshot request(int sequence, String question, boolean terminal) {
        UUID id = UUID.nameUUIDFromBytes(("export-" + sequence).getBytes(StandardCharsets.UTF_8));
        Instant at = NOW.plusSeconds(sequence);
        return new GuideRequestSnapshot(
                id,
                "main",
                GuideTopology.CLIENT_LOCAL,
                question,
                List.of(new GuideTimelineEntry.Assistant(0, "answer-" + sequence, !terminal, List.of())),
                terminal ? GuideRequestStatus.COMPLETED : GuideRequestStatus.MODEL_WAIT,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                at,
                at,
                terminal ? at : null,
                GuideModelSelection.client("default"));
    }

    private static class PagingHistory implements GuideHistoryAccess {
        private final List<GuideHistoryPage> pages;
        private final List<GuideHistoryPageRequest> requests = new ArrayList<>();

        private PagingHistory(List<GuideHistoryPage> pages) {
            this.pages = new ArrayList<>(pages);
        }

        @Override public CompletableFuture<GuideHistoryPage> page(GuideHistoryPageRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(pages.removeFirst());
        }
        @Override public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            return CompletableFuture.completedFuture(GuideHistoryLoad.empty());
        }
        @Override public CompletableFuture<Void> save(GuideHistoryPartition partition) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> commit(GuideHistoryCommit commit) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> delete(GuideHistoryDeleteScope scope) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> resetDatabase() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public GuideHistoryActivity activity() {
            return new GuideHistoryActivity(0, false);
        }
    }
}
