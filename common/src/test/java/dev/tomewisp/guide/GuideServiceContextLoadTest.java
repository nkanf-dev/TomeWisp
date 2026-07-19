package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.agent.context.ContextBudget;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryCommit;
import dev.tomewisp.guide.history.GuideHistoryContextRequest;
import dev.tomewisp.guide.history.GuideHistoryContextSeed;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryMetadata;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceContextLoadTest {
    private static final UUID ACTOR = UUID.fromString("9d2329b1-8db4-4aae-b3ea-d93170532fc0");
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "context.example");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T13:00:00Z"), ZoneOffset.UTC);

    @Test
    void recalculatesDurableContextForEachSelectedProfileBeforeDispatch() {
        ContextHistory history = new ContextHistory();
        ContextLocal local = new ContextLocal();
        GuideService service = service(local, new NoRemote(), history);

        UUID first = success(service.ask("first").join());
        assertEquals(GuideRequestStatus.CONTEXT_LOADING, request(service, first).status());
        assertTrue(local.dispatched.isEmpty());
        assertEquals(64_000, history.requests.getFirst().budget().contextWindowTokens());
        history.completeLatest(seed("old question", "old answer"));
        assertEquals(List.of("small"), local.dispatched);
        assertEquals(List.of("old question", "old answer"), local.hydrated.stream()
                .map(message -> message.content().getFirst())
                .map(content -> ((dev.tomewisp.model.ModelContent.Text) content).text())
                .toList());
        local.finish(first);

        service.setModelSelection(GuideModelSelection.client("large")).join();
        UUID second = success(service.ask("second").join());
        assertEquals(256_000, history.requests.getLast().budget().contextWindowTokens());
        assertEquals("same/model", history.requests.getLast().modelIdentifier());
        history.completeLatest(seed("old question", "old answer"));
        assertEquals(List.of("small", "large"), local.dispatched);
        assertEquals(GuideRequestStatus.MODEL_WAIT, request(service, second).status());
    }

    @Test
    void cancellationAndContextFailureNeverDispatchProvider() {
        ContextHistory history = new ContextHistory();
        ContextLocal local = new ContextLocal();
        GuideService service = service(local, new NoRemote(), history);

        UUID cancelled = success(service.ask("cancel while loading").join());
        assertEquals(Boolean.TRUE,
                ((ToolResult.Success<Boolean>) service.cancel().join()).value());
        history.completeLatest(seed("old", "answer"));
        assertTrue(local.dispatched.isEmpty());
        assertEquals(GuideRequestStatus.CANCELLED, request(service, cancelled).status());

        UUID failed = success(service.ask("failed context").join());
        history.failLatest();
        assertTrue(local.dispatched.isEmpty());
        assertEquals(GuideRequestStatus.FAILED, request(service, failed).status());
        assertEquals("history_context_failed", request(service, failed).failure().code());
    }

    @Test
    void serverModelWithoutAdvertisedBudgetFailsBeforeBridgeDispatch() {
        ContextHistory history = new ContextHistory();
        ContextLocal local = new ContextLocal();
        NoBudgetRemote remote = new NoBudgetRemote();
        GuideService service = service(local, remote, history);
        service.setModelMode(GuideModelMode.SERVER).join();

        UUID request = success(service.ask("server").join());

        assertEquals(GuideRequestStatus.FAILED, request(service, request).status());
        assertEquals("context_budget_unavailable", request(service, request).failure().code());
        assertEquals(0, remote.dispatches);
        assertTrue(history.requests.isEmpty());
    }

    private static GuideService service(
            ContextLocal local, GuideRemoteEndpoint remote, ContextHistory history) {
        return new GuideService(
                ACTOR, local, remote,
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run, CLOCK, new Gson(), SCOPE, history);
    }

    private static GuideHistoryContextSeed seed(String user, String assistant) {
        return new GuideHistoryContextSeed(
                "main", List.of(ModelMessage.userText(user), new dev.tomewisp.model.ModelMessage(
                        dev.tomewisp.model.ModelRole.ASSISTANT,
                        List.of(new dev.tomewisp.model.ModelContent.Text(assistant)))),
                List.of(), 10, new dev.tomewisp.guide.history.GuideHistoryCursor(
                        0, UUID.nameUUIDFromBytes(user.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static GuideRequestSnapshot request(GuideService service, UUID requestId) {
        return service.snapshot().sessions().getFirst().requests().stream()
                .filter(value -> value.requestId().equals(requestId)).findFirst().orElseThrow();
    }

    private static UUID success(ToolResult<UUID> result) {
        return ((ToolResult.Success<UUID>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    private static final class ContextHistory implements GuideHistoryAccess {
        private final List<GuideHistoryContextRequest> requests = new ArrayList<>();
        private final List<CompletableFuture<GuideHistoryContextSeed>> completions = new ArrayList<>();
        @Override public CompletableFuture<Optional<GuideHistoryMetadata>> metadata(
                GuideHistoryScope scope) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<GuideHistoryContextSeed> context(
                GuideHistoryContextRequest request) {
            requests.add(request);
            CompletableFuture<GuideHistoryContextSeed> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        }
        @Override public CompletableFuture<Void> commit(GuideHistoryCommit commit) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            throw new AssertionError("legacy full load");
        }
        @Override public CompletableFuture<Void> save(GuideHistoryPartition partition) {
            throw new AssertionError("legacy full save");
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
        private void completeLatest(GuideHistoryContextSeed seed) { completions.getLast().complete(seed); }
        private void failLatest() { completions.getLast().completeExceptionally(
                new RuntimeException("database detail must stay private")); }
    }

    private static final class ContextLocal implements GuideLocalEndpoint {
        private final Map<UUID, Consumer<AgentEvent>> events = new LinkedHashMap<>();
        private final List<String> dispatched = new ArrayList<>();
        private List<ModelMessage> hydrated = List.of();
        @Override public String defaultProfileId() { return "small"; }
        @Override public List<GuideClientModelProfile> profiles() {
            return List.of(
                    new GuideClientModelProfile("small", "Small", true, true, "same/model", null),
                    new GuideClientModelProfile("large", "Large", true, true, "same/model", null));
        }
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override public Optional<GuideContextSpec> contextSpec(String profileId) {
            return Optional.of(new GuideContextSpec(
                    new ContextBudget(profileId.equals("small") ? 64_000 : 256_000, 4_096),
                    1_000, "same/model"));
        }
        @Override public void hydrateContext(
                UUID actor, String sessionId, List<ModelMessage> messages,
                List<ContextCheckpoint> checkpoints) {
            hydrated = List.copyOf(messages);
        }
        @Override public CompletableFuture<AgentResult> ask(
                UUID actor, String sessionId, UUID requestId, String question,
                ToolInvocationContext context, Consumer<AgentEvent> events) {
            return ask("small", actor, sessionId, requestId, question, context, events);
        }
        @Override public CompletableFuture<AgentResult> ask(
                String profileId, UUID actor, String sessionId, UUID requestId, String question,
                ToolInvocationContext context, Consumer<AgentEvent> sink) {
            dispatched.add(profileId);
            events.put(requestId, sink);
            sink.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            return new CompletableFuture<>();
        }
        @Override public boolean cancel(UUID actor, String sessionId) { return false; }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}
        private void finish(UUID requestId) { events.get(requestId).accept(new AgentEvent.FinalText("done")); }
    }

    private static class NoRemote implements GuideRemoteEndpoint {
        @Override public boolean serverModelAvailable() { return false; }
        @Override public boolean serverToolsAvailable() { return false; }
        @Override public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            return false;
        }
        @Override public boolean cancel(UUID requestId) { return false; }
        @Override public void disconnect() {}
    }

    private static final class NoBudgetRemote extends NoRemote {
        private int dispatches;
        @Override public boolean serverModelAvailable() { return true; }
        @Override public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            dispatches++;
            return true;
        }
    }
}
