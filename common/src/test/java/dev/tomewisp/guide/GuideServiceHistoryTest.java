package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryException;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceHistoryTest {
    private static final UUID ACTOR =
            UUID.fromString("e86bc174-e814-4fb7-a7ca-8ac52158fcad");
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "history.example");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T07:00:00Z"), ZoneOffset.UTC);

    @Test
    void loadsBeforeAcceptingRequestsAndHydratesInterruptedHistory() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);

        assertEquals(GuidePersistenceSnapshot.State.LOADING,
                service.snapshot().persistence().state());
        assertFailure(service.ask("too early").join(), "history_loading");
        assertTrue(local.pending.isEmpty());

        GuideRequestSnapshot interrupted = interruptedRequest();
        history.load.complete(new GuideHistoryLoad(
                java.util.Optional.of(partition(interrupted)), List.of()));

        assertEquals(GuidePersistenceSnapshot.State.AVAILABLE,
                service.snapshot().persistence().state());
        assertEquals(GuideRequestStatus.INTERRUPTED,
                service.snapshot().sessions().getFirst().requests().getFirst().status());
        assertEquals(1, local.hydratedCheckpoints.size());
        assertEquals(1, local.hydratedMessages.size());
        UUID retry = success(service.retry(interrupted.requestId()).join());
        assertNotEquals(interrupted.requestId(), retry);
        assertTrue(local.pending.containsKey(retry));
    }

    @Test
    void persistsSanitizedEventProjectionsAndIgnoresStaleCompletions() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.complete(GuideHistoryLoad.empty());

        UUID request = success(service.ask("persist me").join());
        local.compact(request);
        local.tool(request);

        assertTrue(history.saved.size() >= 3);
        GuideHistoryPartition latest = history.saved.getLast();
        GuideToolActivity persistedTool = latest.sessions().getFirst().requests().getFirst()
                .tools().getFirst();
        assertNull(persistedTool.normalized());
        assertFalse(persistedTool.presentationLines().isEmpty());
        assertEquals(List.of(checkpoint()), latest.sessions().getFirst().checkpoints());

        int latestIndex = history.saveCompletions.size() - 1;
        history.saveCompletions.get(latestIndex).complete(null);
        long committed = service.snapshot().persistence().committedGeneration();
        history.saveCompletions.getFirst().complete(null);

        assertEquals(committed, service.snapshot().persistence().committedGeneration());
        assertEquals(GuidePersistenceSnapshot.State.AVAILABLE,
                service.snapshot().persistence().state());
    }

    @Test
    void writeFailureKeepsInMemoryRequestAndMarksItUnsaved() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.complete(GuideHistoryLoad.empty());

        UUID request = success(service.ask("unsaved").join());
        CompletableFuture<Void> latest = history.saveCompletions.getLast();
        latest.completeExceptionally(new GuideHistoryException(
                "history_write_failed", "injected"));

        assertEquals(request, service.snapshot().sessions().getFirst().requests().getFirst().requestId());
        assertEquals(GuidePersistenceSnapshot.State.UNAVAILABLE,
                service.snapshot().persistence().state());
        assertEquals("history_write_failed", service.snapshot().persistence().failure().code());
    }

    @Test
    void disconnectPersistsCancellationAndNeverWritesEmptyReplacement() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.complete(GuideHistoryLoad.empty());
        UUID request = success(service.ask("disconnect").join());
        history.completeAllSaves();
        int before = history.saved.size();

        CompletableFuture<Void> disconnect = service.disconnect();
        history.completeAllSaves();
        disconnect.join();

        assertEquals(before + 1, history.saved.size());
        GuideRequestSnapshot durable = history.saved.getLast().sessions().getFirst()
                .requests().getFirst();
        assertEquals(request, durable.requestId());
        assertEquals(GuideRequestStatus.CANCELLED, durable.status());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    }

    @Test
    void loadFailureKeepsAgentUsableWithExplicitUnsavedState() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.completeExceptionally(new GuideHistoryException(
                "history_load_failed", "injected"));

        assertEquals(GuidePersistenceSnapshot.State.UNAVAILABLE,
                service.snapshot().persistence().state());
        assertInstanceOf(ToolResult.Success.class, service.ask("memory only").join());
    }

    @Test
    void sendsRecoveredVisibleHistoryToTheSelectedServerModel() {
        FakeHistory history = new FakeHistory();
        FakeRemote remote = new FakeRemote(true);
        GuideService service = service(new FakeLocal(), history, remote);
        GuideRequestSnapshot interrupted = interruptedRequest();
        history.load.complete(new GuideHistoryLoad(
                java.util.Optional.of(partition(interrupted)), List.of()));
        successMode(service.setModelMode(GuideModelMode.SERVER).join());

        success(service.ask("continue remotely").join());

        assertEquals(
                List.of("USER:retry after restart"),
                remote.history.stream()
                        .map(message -> message.role() + ":" + message.text())
                        .toList());
    }

    private static GuideService service(FakeLocal local, FakeHistory history) {
        return service(local, history, new FakeRemote(false));
    }

    private static GuideService service(
            FakeLocal local, FakeHistory history, FakeRemote remote) {
        return new GuideService(
                ACTOR,
                local,
                remote,
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                CLOCK,
                new Gson(),
                SCOPE,
                history);
    }

    private static GuideHistoryPartition partition(GuideRequestSnapshot request) {
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                SCOPE,
                "main",
                GuideModelMode.CLIENT,
                List.of(new GuideSessionSnapshot(
                        "main",
                        List.of(new GuideMessage(
                                request.requestId(), GuideMessage.Role.USER,
                                request.userMessage(), request.createdAt())),
                        List.of(request),
                        List.of(checkpoint()))),
                request.updatedAt());
    }

    private static GuideRequestSnapshot interruptedRequest() {
        Instant terminal = Instant.parse("2026-07-18T06:59:00Z");
        return new GuideRequestSnapshot(
                UUID.fromString("3bfe1fc2-489d-46c4-a17d-a8706dd02863"),
                "main",
                GuideTopology.CLIENT_LOCAL,
                "retry after restart",
                List.of(new GuideTimelineEntry.Assistant(0, "partial", false, List.of())),
                GuideRequestStatus.INTERRUPTED,
                List.of(),
                dev.tomewisp.model.ModelUsage.empty(),
                null,
                new GuideFailure("request_interrupted", "interrupted"),
                terminal.minusSeconds(1),
                terminal,
                terminal);
    }

    private static ContextCheckpoint checkpoint() {
        return new ContextCheckpoint(
                UUID.fromString("b68c674f-b7fd-4c37-bf1d-284139216889"),
                0, 1, "a".repeat(64), "test-model", 1, 1, CLOCK.instant(),
                ContextCheckpoint.Status.SUCCEEDED,
                "{\"goals\":[],\"preferences\":[],\"completedTopics\":[],"
                        + "\"currentTasks\":[],\"decisions\":[],"
                        + "\"unresolvedQuestions\":[],\"evidenceReferences\":[]}",
                null, null, 512);
    }

    private static UUID success(ToolResult<UUID> result) {
        return ((ToolResult.Success<UUID>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    private static GuideModelMode successMode(ToolResult<GuideModelMode> result) {
        return ((ToolResult.Success<GuideModelMode>)
                        assertInstanceOf(ToolResult.Success.class, result))
                .value();
    }

    private static void assertFailure(ToolResult<?> result, String code) {
        assertEquals(code,
                ((ToolResult.Failure<?>) assertInstanceOf(ToolResult.Failure.class, result)).code());
    }

    private static final class FakeHistory implements GuideHistoryAccess {
        private final CompletableFuture<GuideHistoryLoad> load = new CompletableFuture<>();
        private final List<GuideHistoryPartition> saved = new ArrayList<>();
        private final List<CompletableFuture<Void>> saveCompletions = new ArrayList<>();

        @Override
        public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            assertEquals(SCOPE, scope);
            return load;
        }

        @Override
        public CompletableFuture<Void> save(GuideHistoryPartition partition) {
            saved.add(partition);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            saveCompletions.add(completion);
            return completion;
        }

        @Override
        public CompletableFuture<Void> flush() {
            return saveCompletions.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : saveCompletions.getLast().handle((ignored, failure) -> null);
        }

        private void completeAllSaves() {
            saveCompletions.forEach(completion -> completion.complete(null));
        }
    }

    private static final class FakeLocal implements GuideLocalEndpoint {
        private final java.util.Map<UUID, Consumer<AgentEvent>> pending = new java.util.LinkedHashMap<>();
        private final List<GuideMessage> hydratedMessages = new ArrayList<>();
        private final List<ContextCheckpoint> hydratedCheckpoints = new ArrayList<>();

        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override
        public CompletableFuture<AgentResult> ask(
                UUID actor, String sessionId, UUID requestId, String question,
                ToolInvocationContext context, Consumer<AgentEvent> events) {
            pending.put(requestId, events);
            events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            return new CompletableFuture<>();
        }
        @Override public boolean cancel(UUID actor, String sessionId) { return true; }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}

        @Override
        public void hydrateSession(
                UUID actor,
                String sessionId,
                List<GuideMessage> messages,
                List<ContextCheckpoint> checkpoints) {
            hydratedMessages.addAll(messages);
            hydratedCheckpoints.addAll(checkpoints);
        }

        private void compact(UUID request) {
            pending.get(request).accept(new AgentEvent.ContextCompacted(checkpoint()));
        }

        private void tool(UUID request) {
            Consumer<AgentEvent> events = pending.get(request);
            events.accept(new AgentEvent.ToolStarted("call-1", "tomewisp:unknown"));
            JsonObject normalized = new JsonObject();
            normalized.addProperty("status", "success");
            JsonObject value = new JsonObject();
            value.addProperty("visible", "projection");
            normalized.add("value", value);
            events.accept(new AgentEvent.ToolCompleted(
                    "call-1", "tomewisp:unknown", false, normalized));
        }
    }

    private static final class FakeRemote implements GuideRemoteEndpoint {
        private final boolean serverModel;
        private List<GuideMessage> history = List.of();

        private FakeRemote(boolean serverModel) {
            this.serverModel = serverModel;
        }

        @Override public boolean serverModelAvailable() { return serverModel; }
        @Override public boolean serverToolsAvailable() { return false; }
        @Override public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            return false;
        }
        @Override
        public boolean ask(
                UUID requestId,
                String sessionId,
                String question,
                List<GuideMessage> history,
                Consumer<AgentEvent> events) {
            this.history = List.copyOf(history);
            return true;
        }
        @Override public boolean cancel(UUID requestId) { return false; }
        @Override public void disconnect() {}
    }
}
