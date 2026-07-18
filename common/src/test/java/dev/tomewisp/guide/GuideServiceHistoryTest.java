package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
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
    void restoresTheSessionSelectionWithoutRebindingCapturedRequests() {
        FakeHistory history = new FakeHistory();
        GuideService service = service(new FakeLocal(), history);
        GuideRequestSnapshot interrupted = interruptedRequest();
        history.load.complete(new GuideHistoryLoad(
                java.util.Optional.of(partition(
                        interrupted, GuideModelSelection.client("removed-profile"))),
                List.of()));

        GuideSessionSnapshot restored = service.snapshot().sessions().getFirst();

        assertEquals(GuideModelSelection.client("removed-profile"), restored.modelSelection());
        assertEquals(GuideModelSelection.client("default"),
                restored.requests().getFirst().modelSelection());
        assertFailure(service.retry(interrupted.requestId()).join(), "model_not_configured");
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

    @Test
    void actorDeleteRejectsAnActiveRequestInAnySessionWithoutCancellingIt() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.complete(GuideHistoryLoad.empty());
        service.selectSession("other").join();
        history.completeAllSaves();
        UUID active = success(service.ask("stay active").join());
        history.completeAllSaves();

        assertFailure(service.deleteActorHistory().join(), "history_delete_busy");

        assertEquals(active, service.snapshot().sessions().stream()
                .flatMap(session -> session.requests().stream())
                .filter(request -> !request.terminal())
                .findFirst().orElseThrow().requestId());
        assertTrue(history.deletes.isEmpty());
        assertTrue(local.pending.containsKey(active));
    }

    @Test
    void successfulPartitionDeleteBlocksStateChangesAndResetsMemoryWithoutSaving() {
        FakeHistory history = new FakeHistory();
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, history);
        history.load.complete(GuideHistoryLoad.empty());
        service.selectSession("other").join();
        history.completeAllSaves();
        int savesBefore = history.saved.size();

        CompletableFuture<ToolResult<Boolean>> deleting = service.deleteCurrentHistory();

        assertEquals(List.of(GuideHistoryDeleteScope.partition(SCOPE)), history.deletes);
        assertFailure(service.ask("during delete").join(), "history_delete_busy");
        assertFailure(service.selectSession("blocked").join(), "history_delete_busy");
        assertFalse(deleting.isDone());
        history.completeDeletion();

        assertInstanceOf(ToolResult.Success.class, deleting.join());
        assertEquals(List.of("main"), service.snapshot().sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
        assertEquals(GuidePersistenceSnapshot.State.AVAILABLE,
                service.snapshot().persistence().state());
        assertEquals(savesBefore, history.saved.size());
        assertEquals(List.of(ACTOR), local.clearedActors);
    }

    @Test
    void actorDeleteIsBoundToTheCurrentActorAndFailureRetainsExactSnapshot() {
        FakeHistory history = new FakeHistory();
        GuideService service = service(new FakeLocal(), history);
        history.load.complete(GuideHistoryLoad.empty());
        service.selectSession("retained").join();
        history.completeAllSaves();
        GuideSnapshot before = service.snapshot();

        CompletableFuture<ToolResult<Boolean>> deleting = service.deleteActorHistory();
        assertEquals(List.of(GuideHistoryDeleteScope.actor(ACTOR)), history.deletes);
        history.failDeletion();

        assertFailure(deleting.join(), "history_delete_failed");
        assertSame(before, service.snapshot());
        assertInstanceOf(ToolResult.Success.class, service.selectSession("after-failure").join());
    }

    @Test
    void pendingWriteAndUnavailablePersistenceRejectDeletionWithoutRepositoryCall() {
        FakeHistory pending = new FakeHistory();
        GuideService service = service(new FakeLocal(), pending);
        pending.load.complete(GuideHistoryLoad.empty());
        service.selectSession("pending").join();

        assertFailure(service.deleteCurrentHistory().join(), "history_delete_busy");
        assertTrue(pending.deletes.isEmpty());

        FakeHistory unavailable = new FakeHistory();
        GuideService unavailableService = service(new FakeLocal(), unavailable);
        assertFailure(unavailableService.deleteCurrentHistory().join(), "history_loading");
        unavailable.load.completeExceptionally(new GuideHistoryException(
                "history_load_failed", "injected"));
        assertFailure(unavailableService.deleteCurrentHistory().join(), "history_unavailable");
    }

    @Test
    void explicitDatabaseResetCanRecoverAnUnsupportedPreReleaseSchema() {
        FakeHistory history = new FakeHistory();
        GuideService service = service(new FakeLocal(), history);
        history.load.completeExceptionally(new GuideHistoryException(
                "history_schema_unsupported", "future schema"));
        assertEquals(GuidePersistenceSnapshot.State.UNAVAILABLE,
                service.snapshot().persistence().state());

        CompletableFuture<ToolResult<Boolean>> resetting = service.resetHistoryDatabase();

        assertEquals(1, history.resetCalls);
        history.completeDeletion();
        assertInstanceOf(ToolResult.Success.class, resetting.join());
        assertEquals(GuidePersistenceSnapshot.State.AVAILABLE,
                service.snapshot().persistence().state());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    }

    @Test
    void disconnectDuringDeleteLeavesDisconnectedMemoryCleanAndWaitsForTransaction() {
        FakeHistory history = new FakeHistory();
        GuideService service = service(new FakeLocal(), history);
        history.load.complete(GuideHistoryLoad.empty());
        service.selectSession("other").join();
        history.completeAllSaves();
        CompletableFuture<ToolResult<Boolean>> deleting = service.deleteCurrentHistory();

        CompletableFuture<Void> disconnect = service.disconnect();
        assertFalse(disconnect.isDone());
        history.completeDeletion();

        assertInstanceOf(ToolResult.Success.class, deleting.join());
        disconnect.join();
        assertEquals(List.of("main"), service.snapshot().sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
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
        return partition(request, GuideModelSelection.client("default"));
    }

    private static GuideHistoryPartition partition(
            GuideRequestSnapshot request, GuideModelSelection selection) {
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                SCOPE,
                "main",
                List.of(new GuideSessionSnapshot(
                        "main",
                        List.of(new GuideMessage(
                                request.requestId(), GuideMessage.Role.USER,
                                request.userMessage(), request.createdAt())),
                        List.of(request),
                        List.of(checkpoint()),
                        selection)),
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
        private final List<GuideHistoryDeleteScope> deletes = new ArrayList<>();
        private final List<CompletableFuture<Void>> deleteCompletions = new ArrayList<>();
        private int resetCalls;

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
        public CompletableFuture<Void> delete(
                GuideHistoryDeleteScope scope) {
            deletes.add(scope);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            deleteCompletions.add(completion);
            return completion;
        }

        @Override
        public CompletableFuture<Void> resetDatabase() {
            resetCalls++;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            deleteCompletions.add(completion);
            return completion;
        }

        @Override
        public CompletableFuture<Void> flush() {
            if (!deleteCompletions.isEmpty() && !deleteCompletions.getLast().isDone()) {
                return deleteCompletions.getLast().handle((ignored, failure) -> null);
            }
            return saveCompletions.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : saveCompletions.getLast().handle((ignored, failure) -> null);
        }

        @Override
        public GuideHistoryActivity activity() {
            int pending = (int) saveCompletions.stream().filter(value -> !value.isDone()).count();
            boolean deleting = deleteCompletions.stream().anyMatch(value -> !value.isDone());
            return new GuideHistoryActivity(pending, deleting);
        }

        private void completeAllSaves() {
            saveCompletions.forEach(completion -> completion.complete(null));
        }

        private void completeDeletion() {
            deleteCompletions.getLast().complete(null);
        }

        private void failDeletion() {
            deleteCompletions.getLast().completeExceptionally(new GuideHistoryException(
                    "history_delete_failed", "injected"));
        }
    }

    private static final class FakeLocal implements GuideLocalEndpoint {
        private final java.util.Map<UUID, Consumer<AgentEvent>> pending = new java.util.LinkedHashMap<>();
        private final List<GuideMessage> hydratedMessages = new ArrayList<>();
        private final List<ContextCheckpoint> hydratedCheckpoints = new ArrayList<>();
        private final List<UUID> clearedActors = new ArrayList<>();

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
        @Override public void clearActor(UUID actor) { clearedActors.add(actor); }

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
