package dev.tomewisp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.context.ContextBudget;
import dev.tomewisp.agent.context.ContextCompactor;
import dev.tomewisp.agent.context.ToolResultContextReducer;
import dev.tomewisp.agent.context.Utf8ContextTokenEstimator;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.time.Clock;
import org.junit.jupiter.api.Test;

final class GameGuideAgentTest {
    @Test
    void executesRealToolFlowAndReturnsGroundedFinalText() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_1", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("铁锭事实是 42。")));
        FakeTools tools = new FakeTools();
        AgentSessionStore sessions = new AgentSessionStore();
        GameGuideAgent agent = new GameGuideAgent(model, tools, sessions, new Gson());
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = agent.ask(request(UUID.randomUUID()), events::add).join();

        assertTrue(result.successful());
        assertEquals("铁锭事实是 42。", result.text());
        assertEquals(1, tools.invocations.get());
        assertEquals(2, model.requests.size());
        ModelMessage toolResults = model.requests.get(1).messages().getLast();
        assertTrue(toolResults.content().getFirst() instanceof ModelContent.ToolResult);
        assertTrue(result.trace().events().stream().anyMatch(event -> event.type().equals("tool_result")));
        assertEquals(AgentState.COMPLETED, result.trace().finalState());
        assertTrue(events.stream().anyMatch(AgentEvent.FinalText.class::isInstance));
        AgentEvent.ToolStarted started = events.stream()
                .filter(AgentEvent.ToolStarted.class::isInstance)
                .map(AgentEvent.ToolStarted.class::cast)
                .findFirst()
                .orElseThrow();
        AgentEvent.ToolCompleted completed = events.stream()
                .filter(AgentEvent.ToolCompleted.class::isInstance)
                .map(AgentEvent.ToolCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("call_1", started.invocationId());
        assertEquals("call_1", completed.invocationId());
    }

    @Test
    void rejectsBusyAndCancelsPendingModelWithoutLateSuccess() {
        QueueModelClient model = new QueueModelClient();
        CompletableFuture<ModelTurn> pending = new CompletableFuture<>();
        model.enqueue(pending);
        AgentSessionStore sessions = new AgentSessionStore();
        GameGuideAgent agent = new GameGuideAgent(model, new FakeTools(), sessions, new Gson());
        UUID actor = UUID.randomUUID();
        List<AgentEvent> firstEvents = new ArrayList<>();
        CompletableFuture<AgentResult> first = agent.ask(request(actor), firstEvents::add);

        AgentResult busy = agent.ask(request(actor), event -> {}).join();
        assertEquals("agent_busy", busy.errorCode());
        assertTrue(sessions.cancel(new AgentSessionKey(actor, "main")));
        AgentResult cancelled = first.join();
        assertEquals(AgentState.CANCELLED, cancelled.state());
        assertFalse(pending.complete(textTurn("late")));
        assertFalse(firstEvents.stream().anyMatch(AgentEvent.FinalText.class::isInstance));
    }

    @Test
    void allowsDifferentSessionsForTheSameActorToRunConcurrently() {
        ConcurrentModelClient model = new ConcurrentModelClient();
        AgentSessionStore sessions = new AgentSessionStore();
        GameGuideAgent agent = new GameGuideAgent(model, new FakeTools(), sessions, new Gson());
        UUID actor = UUID.randomUUID();

        CompletableFuture<AgentResult> first =
                agent.ask(request(actor, "building"), event -> {});
        CompletableFuture<AgentResult> second =
                agent.ask(request(actor, "recipes"), event -> {});

        assertEquals(2, model.pending.size());
        assertEquals(2, model.maxConcurrent.get());
        model.pending.get("building").complete(textTurn("建筑会话"));
        model.pending.get("recipes").complete(textTurn("配方会话"));
        assertEquals("建筑会话", first.join().text());
        assertEquals("配方会话", second.join().text());
    }

    @Test
    void aSessionTranscriptSurvivesSwitchingModelClients() {
        QueueModelClient firstModel = new QueueModelClient();
        firstModel.enqueue(CompletableFuture.completedFuture(textTurn("first answer")));
        QueueModelClient secondModel = new QueueModelClient();
        secondModel.enqueue(CompletableFuture.completedFuture(textTurn("second answer")));
        AgentSessionStore sessions = new AgentSessionStore();
        UUID actor = UUID.randomUUID();

        new GameGuideAgent(firstModel, new FakeTools(), sessions, new Gson())
                .ask(request(actor), ignored -> {})
                .join();
        new GameGuideAgent(secondModel, new FakeTools(), sessions, new Gson())
                .ask(request(actor), ignored -> {})
                .join();

        assertEquals(3, secondModel.requests.getFirst().messages().size());
        assertEquals(
                "first answer",
                ((ModelContent.Text) secondModel.requests.getFirst()
                                .messages().get(1).content().getFirst())
                        .text());
    }

    @Test
    void failsAnIdenticalConsecutiveToolCallWithoutInvokingAgain() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_1", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_2", 42)));
        FakeTools tools = new FakeTools();
        AgentResult result = new GameGuideAgent(
                        model, tools, new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), event -> {})
                .join();

        assertEquals(AgentState.FAILED, result.state());
        assertEquals("repeated_tool_call", result.errorCode());
        assertEquals(1, tools.invocations.get());
        assertNotNull(result.trace());
    }

    @Test
    void compactsBeforePrimaryDispatchAndCommitsCompleteUnreducedHistory() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(textTurn(summaryJson())));
        model.enqueue(CompletableFuture.completedFuture(textTurn("final")));
        AgentSessionStore sessions = new AgentSessionStore();
        UUID actor = UUID.randomUUID();
        AgentSessionKey key = new AgentSessionKey(actor, "main");
        List<ModelMessage> original = List.of(
                ModelMessage.userText("a".repeat(250)),
                ModelMessage.userText("b".repeat(250)),
                ModelMessage.userText("c".repeat(250)),
                ModelMessage.userText("d".repeat(250)));
        sessions.hydrate(key, original);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = new GameGuideAgent(
                        model, new FakeTools(), sessions, new Gson(), compactor(model))
                .ask(request(actor), events::add)
                .join();

        assertTrue(result.successful());
        assertEquals(2, model.requests.size());
        assertEquals("final", result.text());
        assertTrue(events.stream().anyMatch(event -> event.equals(
                new AgentEvent.StateChanged(AgentState.COMPACTING))));
        assertTrue(events.stream().anyMatch(AgentEvent.ContextCompacted.class::isInstance));
        assertEquals(1, sessions.checkpoints(key).size());
        assertEquals(6, sessions.status(key).historyMessages());
        assertTrue(model.requests.get(1).messages().getFirst().content().stream()
                .map(ModelContent.Text.class::cast)
                .anyMatch(text -> text.text().contains("NOT factual evidence")));
    }

    @Test
    void compactionFailureAndCancellationNeverDispatchPrimaryOrReplaceHistory() {
        UUID actor = UUID.randomUUID();
        AgentSessionKey key = new AgentSessionKey(actor, "main");
        AgentSessionStore failedSessions = new AgentSessionStore();
        failedSessions.hydrate(key, largeHistory());
        QueueModelClient malformed = new QueueModelClient();
        malformed.enqueue(CompletableFuture.completedFuture(textTurn("not-json")));

        AgentResult failed = new GameGuideAgent(
                        malformed, new FakeTools(), failedSessions, new Gson(), compactor(malformed))
                .ask(request(actor), ignored -> {})
                .join();

        assertEquals("context_compaction_failed", failed.errorCode());
        assertEquals(1, malformed.requests.size());
        assertEquals(4, failedSessions.status(key).historyMessages());

        AgentSessionStore cancelledSessions = new AgentSessionStore();
        cancelledSessions.hydrate(key, largeHistory());
        QueueModelClient pendingModel = new QueueModelClient();
        CompletableFuture<ModelTurn> pending = new CompletableFuture<>();
        pendingModel.enqueue(pending);
        CompletableFuture<AgentResult> running = new GameGuideAgent(
                        pendingModel, new FakeTools(), cancelledSessions, new Gson(), compactor(pendingModel))
                .ask(request(actor), ignored -> {});
        assertTrue(cancelledSessions.cancel(key));

        assertEquals(AgentState.CANCELLED, running.join().state());
        assertEquals(1, pendingModel.requests.size());
        assertEquals(4, cancelledSessions.status(key).historyMessages());
    }

    private static ContextCompactor compactor(ModelClient model) {
        return new ContextCompactor(
                model, new Gson(), new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(), new ContextBudget(1_200, 100),
                "test-model", Clock.systemUTC());
    }

    private static List<ModelMessage> largeHistory() {
        return List.of(
                ModelMessage.userText("a".repeat(250)),
                ModelMessage.userText("b".repeat(250)),
                ModelMessage.userText("c".repeat(250)),
                ModelMessage.userText("d".repeat(250)));
    }

    private static String summaryJson() {
        return """
                {"goals":[],"preferences":[],"completedTopics":[],"currentTasks":[],
                 "decisions":[],"unresolvedQuestions":[],"evidenceReferences":[]}
                """;
    }

    private static AgentRequest request(UUID actor) {
        return request(actor, "main");
    }

    private static AgentRequest request(UUID actor, String sessionId) {
        return new AgentRequest(
                UUID.randomUUID(),
                actor,
                sessionId,
                "铁锭怎么做？",
                "Always use tools for dynamic facts.",
                ToolInvocationContext.developmentConsole("agent-test"),
                true);
    }

    private static final class ConcurrentModelClient implements ModelClient {
        private final java.util.Map<String, CompletableFuture<ModelTurn>> pending =
                new java.util.HashMap<>();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            int active = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(active, Math::max);
            CompletableFuture<ModelTurn> future = new CompletableFuture<>();
            pending.put(request.sessionKey().substring(request.sessionKey().lastIndexOf(':') + 1), future);
            future.whenComplete((ignored, throwable) -> concurrent.decrementAndGet());
            cancellation.onCancel(() -> future.completeExceptionally(new ModelClientException(
                    new ModelFailure("agent_cancelled", "cancelled", null))));
            return future;
        }
    }

    private static ModelTurn toolTurn(String callId, int value) {
        JsonObject input = new JsonObject();
        input.addProperty("value", value);
        return new ModelTurn(
                "test",
                "test-model",
                List.of(new ModelContent.ToolUse(callId, "test__fact", input)),
                "tool_use",
                ModelUsage.empty());
    }

    private static ModelTurn textTurn(String text) {
        return new ModelTurn(
                "test",
                "test-model",
                List.of(new ModelContent.Text(text)),
                "end_turn",
                ModelUsage.empty());
    }

    private static final class QueueModelClient implements ModelClient {
        private final Deque<CompletableFuture<ModelTurn>> turns = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        void enqueue(CompletableFuture<ModelTurn> turn) {
            turns.add(turn);
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            CompletableFuture<ModelTurn> turn = turns.removeFirst();
            cancellation.onCancel(() -> turn.completeExceptionally(new ModelClientException(
                    new ModelFailure("agent_cancelled", "cancelled", null))));
            return turn;
        }
    }

    private static final class FakeTools implements AgentToolExecutor {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public List<ModelToolDefinition> definitions() {
            return List.of(new ModelToolDefinition(
                    "test__fact",
                    "Return a fact",
                    JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()));
        }

        @Override
        public Set<ContextCapability> requiredContext() {
            return Set.of();
        }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            invocations.incrementAndGet();
            JsonObject value = new JsonObject();
            value.addProperty("fact", arguments.get("value").getAsInt());
            JsonObject normalized = new JsonObject();
            normalized.addProperty("status", "success");
            normalized.addProperty("outputType", "test.Output");
            normalized.add("value", value);
            return CompletableFuture.completedFuture(
                    new AgentToolResult("test:fact", normalized, false));
        }
    }
}
