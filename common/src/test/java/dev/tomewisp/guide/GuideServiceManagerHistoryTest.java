package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryMetadata;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceManagerHistoryTest {
    private static final UUID ACTOR =
            UUID.fromString("77a70151-0279-4ee6-a6b9-b15c24d08f05");

    @Test
    void waitsForPreviousDisconnectBeforeLoadingReplacementScope() {
        QueuedDispatcher dispatcher = new QueuedDispatcher();
        RecordingHistory history = new RecordingHistory();
        GuideHistoryScope first = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "first.example");
        GuideHistoryScope second = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "second.example");
        GuideHistoryScope[] selected = {first};
        GuideServiceManager manager = new GuideServiceManager(
                new IdleLocal(),
                new IdleRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                dispatcher,
                Clock.systemUTC(),
                new Gson(),
                history,
                actor -> selected[0]);

        manager.forActor(ACTOR);
        assertEquals(List.of(first), history.loads);
        selected[0] = second;

        GuideService replacement = manager.forActor(ACTOR);

        assertEquals(GuidePersistenceSnapshot.State.LOADING,
                replacement.snapshot().persistence().state());
        assertEquals(List.of(first), history.loads);
        dispatcher.runAll();
        assertEquals(List.of(first, second), history.loads);
    }

    @Test
    void managerResetUsesCurrentServiceGateAndResetsOnlyAfterCommit() {
        RecordingHistory history = new RecordingHistory();
        GuideHistoryScope scope = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "reset.example");
        GuideServiceManager manager = new GuideServiceManager(
                new IdleLocal(),
                new IdleRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                Clock.systemUTC(),
                new Gson(),
                history,
                actor -> scope);
        GuideService service = manager.forActor(ACTOR);
        service.selectSession("other").join();

        CompletableFuture<ToolResult<Boolean>> resetting = manager.resetHistoryDatabase();

        assertEquals(1, history.resetCalls);
        assertFalse(resetting.isDone());
        assertFailure(service.ask("during reset").join(), "history_delete_busy");
        history.reset.complete(null);

        assertInstanceOf(ToolResult.Success.class, resetting.join());
        assertEquals(List.of("main"), service.snapshot().sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    }

    @Test
    void managerResetWithoutCurrentServiceIsUnavailable() {
        GuideServiceManager manager = new GuideServiceManager(
                new IdleLocal(),
                new IdleRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                Clock.systemUTC(),
                new Gson());

        assertFailure(manager.resetHistoryDatabase().join(), "history_unavailable");
    }

    @Test
    void settingsSnapshotExposesOnlyFriendlyScopeAndCurrentGuideState() {
        RecordingHistory history = new RecordingHistory();
        GuideHistoryScope scope = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "private.example");
        GuideServiceManager manager = new GuideServiceManager(
                new IdleLocal(),
                new IdleRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                Clock.systemUTC(),
                new Gson(),
                history,
                actor -> scope);

        GuideHistorySettingsSnapshot disconnected = manager.historySettingsSnapshot();
        assertTrue(disconnected.configured());
        assertTrue(disconnected.guide().isEmpty());
        assertTrue(disconnected.scopeKind().isEmpty());

        manager.forActor(ACTOR);
        GuideHistorySettingsSnapshot connected = manager.historySettingsSnapshot();

        assertEquals(ACTOR, connected.guide().orElseThrow().actorId());
        assertEquals(GuideHistoryScope.Kind.MULTIPLAYER,
                connected.scopeKind().orElseThrow());
        assertFalse(connected.toString().contains("private.example"));
    }

    private static void assertFailure(ToolResult<?> result, String code) {
        assertEquals(code,
                ((ToolResult.Failure<?>) assertInstanceOf(ToolResult.Failure.class, result)).code());
    }

    private static final class QueuedDispatcher implements dev.tomewisp.client.ClientEventDispatcher {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable event) {
            queued.add(event);
        }

        private void runAll() {
            while (!queued.isEmpty()) {
                queued.remove().run();
            }
        }
    }

    private static final class RecordingHistory implements GuideHistoryAccess {
        private final List<GuideHistoryScope> loads = new ArrayList<>();
        private final CompletableFuture<Void> reset = new CompletableFuture<>();
        private int resetCalls;

        @Override
        public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            loads.add(scope);
            return CompletableFuture.completedFuture(GuideHistoryLoad.empty());
        }

        @Override
        public CompletableFuture<java.util.Optional<GuideHistoryMetadata>> metadata(
                GuideHistoryScope scope) {
            loads.add(scope);
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }

        @Override
        public CompletableFuture<Void> save(GuideHistoryPartition partition) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(
                GuideHistoryDeleteScope scope) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> resetDatabase() {
            resetCalls++;
            return reset;
        }

        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public GuideHistoryActivity activity() {
            return new GuideHistoryActivity(0, resetCalls > 0 && !reset.isDone());
        }
    }

    private static final class IdleLocal implements GuideLocalEndpoint {
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override
        public CompletableFuture<dev.tomewisp.agent.AgentResult> ask(
                UUID actor, String sessionId, UUID requestId, String question,
                ToolInvocationContext context, Consumer<AgentEvent> events) {
            return new CompletableFuture<>();
        }
        @Override public boolean cancel(UUID actor, String sessionId) { return false; }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}
    }

    private static final class IdleRemote implements GuideRemoteEndpoint {
        @Override public boolean serverModelAvailable() { return false; }
        @Override public boolean serverToolsAvailable() { return false; }
        @Override
        public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            return false;
        }
        @Override public boolean cancel(UUID requestId) { return false; }
        @Override public void disconnect() {}
    }
}
