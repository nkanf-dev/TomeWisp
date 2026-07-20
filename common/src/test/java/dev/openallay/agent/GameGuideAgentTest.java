package dev.openallay.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.session.AgentSessionKey;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.agent.context.ContextCompactor;
import dev.openallay.agent.context.ToolResultContextReducer;
import dev.openallay.agent.context.Utf8ContextTokenEstimator;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.tool.CompositeAgentToolExecutor;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelClientException;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelFailure;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
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
    void canonicalProviderAliasProducesCanonicalEventsAndCompletes() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(
                toolTurn("call_alias", "test:fact", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("Alias recovered.")));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = new GameGuideAgent(
                        model, new FakeTools(), new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), events::add)
                .join();

        assertTrue(result.successful());
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
        assertEquals("test:fact", started.toolId());
        assertEquals("test:fact", completed.toolId());
    }

    @Test
    void unknownModelToolBecomesAToolResultAndTheModelCanRecover() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(
                toolTurn("call_unknown", "openallay:invented", 42)));
        model.enqueue(CompletableFuture.completedFuture(
                textTurn("That capability is unavailable.")));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = new GameGuideAgent(
                        model,
                        new CompositeAgentToolExecutor(List.of(new FakeTools())),
                        new AgentSessionStore(),
                        new Gson())
                .ask(request(UUID.randomUUID()), events::add)
                .join();

        assertTrue(result.successful());
        assertEquals("That capability is unavailable.", result.text());
        ModelContent.ToolResult toolResult = (ModelContent.ToolResult) model.requests.get(1)
                .messages().getLast().content().getFirst();
        assertTrue(toolResult.error());
        assertTrue(toolResult.text().contains("code: tool_unavailable"));
        assertTrue(events.stream()
                .filter(AgentEvent.ToolStarted.class::isInstance)
                .map(AgentEvent.ToolStarted.class::cast)
                .allMatch(started -> started.toolId().equals(AgentToolExecutor.UNKNOWN_TOOL_ID)));
    }

    @Test
    void executorExceptionBecomesAToolResultAndTheModelCanRecover() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_failure", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("The lookup failed.")));

        AgentResult result = new GameGuideAgent(
                        model, new FailingTools(), new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), ignored -> {})
                .join();

        assertTrue(result.successful());
        ModelContent.ToolResult toolResult = (ModelContent.ToolResult) model.requests.get(1)
                .messages().getLast().content().getFirst();
        assertTrue(toolResult.error());
        assertTrue(toolResult.text().contains("code: tool_failure"));
    }

    @Test
    void sameTurnToolsStartTogetherAndReturnToModelInOriginalOrder() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(multiToolTurn(
                new ToolCall("call_first", 1), new ToolCall("call_second", 2))));
        model.enqueue(CompletableFuture.completedFuture(textTurn("done")));
        PendingTools tools = new PendingTools();
        GameGuideAgent agent = new GameGuideAgent(
                model, tools, new AgentSessionStore(), new Gson());

        CompletableFuture<AgentResult> result =
                agent.ask(request(UUID.randomUUID()), ignored -> {});

        assertEquals(Set.of(1, 2), tools.pending.keySet(),
                "the second same-turn call must start before the first completes");
        tools.complete(2);
        assertFalse(result.isDone());
        tools.complete(1);
        assertEquals("done", result.join().text());
        List<ModelContent.ToolResult> results = model.requests.get(1).messages().getLast()
                .content().stream()
                .map(ModelContent.ToolResult.class::cast)
                .toList();
        assertEquals(List.of("call_first", "call_second"),
                results.stream().map(ModelContent.ToolResult::toolUseId).toList());
        assertTrue(results.get(0).text().contains("fact: 1"));
        assertTrue(results.get(1).text().contains("fact: 2"));
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
    void givesModelOneNoNewInformationResultBeforeRequiringFinalText() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_1", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_2", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("I already checked; the result is unchanged.")));
        FakeTools tools = new FakeTools();
        List<AgentEvent> events = new ArrayList<>();
        AgentResult result = new GameGuideAgent(
                        model, tools, new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), events::add)
                .join();

        assertEquals(AgentState.COMPLETED, result.state());
        assertEquals("I already checked; the result is unchanged.", result.text());
        assertEquals(1, tools.invocations.get());
        assertEquals(1, events.stream().filter(AgentEvent.ToolStarted.class::isInstance).count());
        ModelContent.ToolResult repeated = (ModelContent.ToolResult) model.requests.get(2)
                .messages().getLast().content().getFirst();
        assertTrue(repeated.text().contains("code: no_new_information"));
        assertTrue(repeated.error());
        assertNotNull(result.trace());
    }

    @Test
    void secondNoProgressAttemptRequestsFinalSynthesisBeforeTermination() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_1", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_2", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_3", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("final from retained evidence")));
        FakeTools tools = new FakeTools();

        AgentResult result = new GameGuideAgent(
                        model, tools, new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), ignored -> {})
                .join();

        assertEquals(AgentState.COMPLETED, result.state());
        assertEquals("final from retained evidence", result.text());
        assertEquals(1, tools.invocations.get());
        ModelContent.ToolResult finalGuidance = (ModelContent.ToolResult) model.requests.get(3)
                .messages().getLast().content().getFirst();
        assertTrue(finalGuidance.text().contains("Produce the final answer now"));
    }

    @Test
    void failsPreciselyIfModelStillCallsTheSameToolAfterFinalSynthesisGuidance() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_1", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_2", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_3", 42)));
        model.enqueue(CompletableFuture.completedFuture(toolTurn("call_4", 42)));
        FakeTools tools = new FakeTools();

        AgentResult result = new GameGuideAgent(
                        model, tools, new AgentSessionStore(), new Gson())
                .ask(request(UUID.randomUUID()), ignored -> {})
                .join();

        assertEquals(AgentState.FAILED, result.state());
        assertEquals("repeated_tool_call", result.errorCode());
        assertEquals(1, tools.invocations.get());
    }

    @Test
    void compactsBeforePrimaryDispatchAndKeepsOnlyTheSuccessfulRuntimeProjection() {
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
        assertEquals(5, sessions.status(key).historyMessages());
        assertTrue(model.requests.get(1).messages().getFirst().content().stream()
                .map(ModelContent.Text.class::cast)
                .anyMatch(text -> text.text().contains("NOT factual evidence")));
    }

    @Test
    void budgetsEveryToolContinuationBeforeProviderDispatch() {
        QueueModelClient model = new QueueModelClient();
        model.enqueue(CompletableFuture.completedFuture(toolTurn("large_call", 42)));
        model.enqueue(CompletableFuture.completedFuture(textTurn("bounded")));
        ContextBudget budget = new ContextBudget(1_200, 100);
        ContextCompactor compactor = new ContextCompactor(
                model, new Gson(), new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(), budget, "test-model", Clock.systemUTC());

        AgentResult result = new GameGuideAgent(
                        model, new LargeResultTools(), new AgentSessionStore(), new Gson(), compactor)
                .ask(request(UUID.randomUUID()), ignored -> {})
                .join();

        assertEquals("bounded", result.text());
        Utf8ContextTokenEstimator estimator = new Utf8ContextTokenEstimator();
        assertEquals(2, model.requests.size(), "a Tool continuation must not require a summary call");
        assertTrue(model.requests.stream().allMatch(request -> estimator.estimate(
                request.systemPrompt(), request.messages(), request.tools()) <= budget.inputTokens()));
        ModelContent.ToolResult projected = (ModelContent.ToolResult) model.requests.get(1)
                .messages().getLast().content().getFirst();
        assertTrue(projected.text().contains("resource: /result/large"));
        assertFalse(projected.text().contains("row-199"));
    }

    @Test
    void repeatedLargeToolTurnsStayWithinResolvedOneHundredKWindow() {
        QueueModelClient model = new QueueModelClient();
        for (int index = 0; index < 12; index++) {
            model.enqueue(CompletableFuture.completedFuture(toolTurn("large_" + index, index)));
        }
        model.enqueue(CompletableFuture.completedFuture(textTurn("complete")));
        ContextBudget budget = new ContextBudget(100_000, 4_096);
        ContextCompactor compactor = new ContextCompactor(
                model, new Gson(), new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(), budget, "100k-model", Clock.systemUTC());

        AgentResult result = new GameGuideAgent(
                        model, new LargeResultTools(), new AgentSessionStore(), new Gson(), compactor)
                .ask(request(UUID.randomUUID()), ignored -> {})
                .join();

        assertEquals("complete", result.text());
        assertEquals(13, model.requests.size());
        Utf8ContextTokenEstimator estimator = new Utf8ContextTokenEstimator();
        assertTrue(model.requests.stream().allMatch(request -> estimator.estimate(
                request.systemPrompt(), request.messages(), request.tools()) <= budget.inputTokens()));
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
        return toolTurn(callId, "test__fact", value);
    }

    private static ModelTurn toolTurn(String callId, String toolName, int value) {
        JsonObject input = new JsonObject();
        input.addProperty("value", value);
        return new ModelTurn(
                "test",
                "test-model",
                List.of(new ModelContent.ToolUse(callId, toolName, input)),
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

    private record ToolCall(String id, int value) {}

    private static ModelTurn multiToolTurn(ToolCall... calls) {
        return new ModelTurn(
                "test",
                "test-model",
                java.util.Arrays.stream(calls).map(call -> {
                    JsonObject input = new JsonObject();
                    input.addProperty("value", call.value());
                    return (ModelContent) new ModelContent.ToolUse(
                            call.id(), "test__fact", input);
                }).toList(),
                "tool_use",
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

    private static class FakeTools implements AgentToolExecutor {
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
        public Optional<String> canonicalToolId(String modelToolName) {
            return switch (modelToolName) {
                case "test__fact", "test:fact" -> Optional.of("test:fact");
                default -> Optional.empty();
            };
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

    private static final class FailingTools implements AgentToolExecutor {
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
        public Optional<String> canonicalToolId(String modelToolName) {
            return "test__fact".equals(modelToolName)
                    ? Optional.of("test:fact")
                    : Optional.empty();
        }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            return CompletableFuture.failedFuture(new IllegalStateException("raw private failure"));
        }
    }

    private static final class LargeResultTools extends FakeTools {
        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            JsonObject normalized = new JsonObject();
            normalized.addProperty("status", "success");
            normalized.addProperty("outputType", "test.Large");
            JsonObject value = new JsonObject();
            value.addProperty("bulk", "x".repeat(40_000));
            normalized.add("value", value);
            StringBuilder text = new StringBuilder(
                    "status: success\ngeneration: g1\nkind: table\nreturned: 200/200\n");
            for (int index = 0; index < 200; index++) {
                text.append("row-").append(index).append(": ").append("x".repeat(80)).append('\n');
            }
            dev.openallay.resource.projection.ResourceReceipt receipt =
                    new dev.openallay.resource.projection.ResourceReceipt(
                            dev.openallay.resource.vfs.ResourcePath.parse("/result/large"),
                            "g1", "table", 200, 200L, List.of("row"), "cursor-next");
            dev.openallay.agent.tool.ModelToolResultView modelView =
                    new dev.openallay.agent.tool.ModelToolResultView(
                            text.toString().stripTrailing(), List.of(receipt), text.length());
            return CompletableFuture.completedFuture(new AgentToolResult(
                    "test:fact", normalized, modelView,
                    new dev.openallay.agent.tool.ToolUiReference(
                            dev.openallay.resource.vfs.ResourcePath.parse("/result/large"),
                            List.of(), dev.openallay.resource.vfs.ResourcePresentation.Kind.NONE),
                    new dev.openallay.agent.tool.ToolResultDiagnostics(
                            40_000, text.length(), "g1", java.time.Instant.EPOCH),
                    false));
        }
    }

    private static final class PendingTools implements AgentToolExecutor {
        private final java.util.Map<Integer, CompletableFuture<AgentToolResult>> pending =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<ModelToolDefinition> definitions() {
            return List.of(new ModelToolDefinition(
                    "test__fact", "Return a fact",
                    JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()));
        }

        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }

        @Override
        public Optional<String> canonicalToolId(String modelToolName) {
            return Optional.of("test:fact");
        }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            int value = arguments.get("value").getAsInt();
            CompletableFuture<AgentToolResult> result = new CompletableFuture<>();
            pending.put(value, result);
            return result;
        }

        private void complete(int value) {
            JsonObject fact = new JsonObject();
            fact.addProperty("fact", value);
            JsonObject normalized = new JsonObject();
            normalized.addProperty("status", "success");
            normalized.addProperty("outputType", "test.Output");
            normalized.add("value", fact);
            pending.get(value).complete(new AgentToolResult("test:fact", normalized, false));
        }
    }
}
