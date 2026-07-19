package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryCommit;
import dev.tomewisp.guide.history.GuideHistoryCursor;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryMetadata;
import dev.tomewisp.guide.history.GuideHistoryPage;
import dev.tomewisp.guide.history.GuideHistoryPageRequest;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceHistoryWindowTest {
    private static final UUID ACTOR = UUID.fromString("fa7cf22f-6632-4fb6-b31a-bff27de35000");
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "window.example");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void startupLoadsOnlyMetadataAndPublishesCountsWithoutBodies() {
        WindowHistory history = new WindowHistory(metadata());
        GuideService service = service(history);

        GuideSessionSnapshot session = service.snapshot().sessions().getFirst();
        assertEquals(3, session.historyWindow().totalRequests());
        assertTrue(session.requests().isEmpty());
        assertEquals(0, history.legacyLoads);
        assertEquals(1, history.metadataLoads);
    }

    @Test
    void coalescesIdenticalPagesAndSuppressesSupersededLateCompletion() {
        WindowHistory history = new WindowHistory(metadata());
        GuideService service = service(history);
        GuideHistoryCursor last = metadata().sessions().getFirst().last();

        CompletableFuture<ToolResult<GuideHistoryPage>> first = service.requestHistoryWindow(
                "main", GuideHistoryPageRequest.Direction.NEWEST, null, 2);
        CompletableFuture<ToolResult<GuideHistoryPage>> duplicate = service.requestHistoryWindow(
                "main", GuideHistoryPageRequest.Direction.NEWEST, null, 2);
        assertEquals(1, history.pages.size());

        CompletableFuture<ToolResult<GuideHistoryPage>> replacement = service.requestHistoryWindow(
                "main", GuideHistoryPageRequest.Direction.BEFORE, last, 1);
        assertFailure(first.join(), "history_page_superseded");
        assertFailure(duplicate.join(), "history_page_superseded");
        assertEquals(2, history.pages.size());

        history.pages.getFirst().complete(page(List.of(request(1), request(2)), true, false));
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());

        GuideHistoryPage accepted = page(List.of(request(1)), false, true);
        history.pages.getLast().complete(accepted);
        assertInstanceOf(ToolResult.Success.class, replacement.join());
        GuideSessionSnapshot session = service.snapshot().sessions().getFirst();
        assertEquals(List.of(request(1).requestId()),
                session.requests().stream().map(GuideRequestSnapshot::requestId).toList());
        assertEquals(GuideHistoryPageState.IDLE, session.historyWindow().state());
    }

    @Test
    void disconnectInvalidatesOutstandingPageWaiters() {
        WindowHistory history = new WindowHistory(metadata());
        GuideService service = service(history);
        CompletableFuture<ToolResult<GuideHistoryPage>> page = service.requestHistoryWindow(
                "main", GuideHistoryPageRequest.Direction.NEWEST, null, 2);

        service.disconnect().join();

        assertFailure(page.join(), "history_page_cancelled");
        history.pages.getFirst().complete(page(List.of(request(1)), false, true));
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    }

    private static GuideService service(WindowHistory history) {
        return new GuideService(
                ACTOR, null, new NoRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run, CLOCK, new Gson(), SCOPE, history);
    }

    private static GuideHistoryMetadata metadata() {
        GuideHistoryCursor first = new GuideHistoryCursor(0, request(0).requestId());
        GuideHistoryCursor last = new GuideHistoryCursor(2, request(2).requestId());
        return new GuideHistoryMetadata(
                SCOPE, "main",
                List.of(new GuideHistoryMetadata.Session(
                        "main", 0, GuideModelSelection.client("default"), 3, first, last)),
                CLOCK.instant());
    }

    private static GuideHistoryPage page(
            List<GuideRequestSnapshot> requests, boolean earlier, boolean later) {
        if (requests.isEmpty()) return new GuideHistoryPage("main", List.of(), null, null, earlier, later);
        long firstSequence = requests.getFirst().userMessage().charAt(1) - '0';
        long lastSequence = requests.getLast().userMessage().charAt(1) - '0';
        return new GuideHistoryPage(
                "main", requests,
                new GuideHistoryCursor(firstSequence, requests.getFirst().requestId()),
                new GuideHistoryCursor(lastSequence, requests.getLast().requestId()),
                earlier, later);
    }

    private static GuideRequestSnapshot request(int sequence) {
        UUID id = UUID.nameUUIDFromBytes(("request-" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant at = CLOCK.instant().minusSeconds(10 - sequence);
        return new GuideRequestSnapshot(
                id, "main", GuideTopology.CLIENT_LOCAL, "q" + sequence,
                List.of(new GuideTimelineEntry.Assistant(0, "a" + sequence, false, List.of())),
                GuideRequestStatus.COMPLETED, List.of(), ModelUsage.empty(), null, null,
                at, at, at, GuideModelSelection.client("default"));
    }

    private static void assertFailure(ToolResult<?> result, String code) {
        assertEquals(code,
                ((ToolResult.Failure<?>) assertInstanceOf(ToolResult.Failure.class, result)).code());
    }

    private static final class WindowHistory implements GuideHistoryAccess {
        private final GuideHistoryMetadata metadata;
        private final List<CompletableFuture<GuideHistoryPage>> pages = new ArrayList<>();
        private int metadataLoads;
        private int legacyLoads;

        private WindowHistory(GuideHistoryMetadata metadata) { this.metadata = metadata; }
        @Override public CompletableFuture<java.util.Optional<GuideHistoryMetadata>> metadata(
                GuideHistoryScope scope) {
            metadataLoads++;
            return CompletableFuture.completedFuture(java.util.Optional.of(metadata));
        }
        @Override public CompletableFuture<GuideHistoryPage> page(GuideHistoryPageRequest request) {
            CompletableFuture<GuideHistoryPage> result = new CompletableFuture<>();
            pages.add(result);
            return result;
        }
        @Override public CompletableFuture<Void> commit(GuideHistoryCommit commit) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            legacyLoads++;
            return CompletableFuture.completedFuture(GuideHistoryLoad.empty());
        }
        @Override public CompletableFuture<Void> save(GuideHistoryPartition partition) {
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
        @Override public GuideHistoryActivity activity() { return new GuideHistoryActivity(0, false); }
    }

    private static final class NoRemote implements GuideRemoteEndpoint {
        @Override public boolean serverModelAvailable() { return false; }
        @Override public boolean serverToolsAvailable() { return false; }
        @Override public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            return false;
        }
        @Override public boolean cancel(UUID requestId) { return false; }
        @Override public void disconnect() {}
    }
}
