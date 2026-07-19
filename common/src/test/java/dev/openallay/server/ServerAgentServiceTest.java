package dev.openallay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.Gson;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.ServerAgentEventPayload;
import dev.openallay.bridge.protocol.ServerAgentHistoryMessage;
import dev.openallay.bridge.protocol.ServerAgentRequestPayload;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ServerAgentServiceTest {
    @Test
    void sameSessionIsBusyButDifferentSessionsForOnePlayerRunConcurrently() {
        PendingModel model = new PendingModel();
        AgentSessionStore sessions = new AgentSessionStore();
        AgentToolExecutor tools = new EmptyTools();
        List<ServerAgentEventPayload> events = new ArrayList<>();
        ServerAgentService service = new ServerAgentService(
                new GameGuideAgent(model, tools, sessions, new Gson()),
                tools,
                sessions,
                (actor, capabilities, id) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(id)),
                (actor, event) -> events.add(event),
                new Gson(),
                "system");
        UUID actor = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();

        assertInstanceOf(ToolResult.Success.class, service.ask(actor, request(firstId, "main")));
        assertInstanceOf(ToolResult.Success.class, service.ask(actor, request(UUID.randomUUID(), "main")));
        assertInstanceOf(ToolResult.Success.class, service.ask(actor, request(UUID.randomUUID(), "other")));
        assertEquals(2, model.pending.size());
        assertEquals(2, service.activeRequests());

        model.pending.get("main").complete(turn("main answer"));
        model.pending.get("other").complete(turn("other answer"));
        assertEquals(0, service.activeRequests());
        assertEquals(3, events.stream().filter(ServerAgentEventPayload::terminal).count());
        assertEquals(1, events.stream().filter(event -> event.eventJson().contains("agent_busy")).count());
    }

    @Test
    void atomicallyRestoresVisibleHistoryBeforeTheCurrentQuestion() {
        PendingModel model = new PendingModel();
        AgentSessionStore sessions = new AgentSessionStore();
        AgentToolExecutor tools = new EmptyTools();
        ServerAgentService service = new ServerAgentService(
                new GameGuideAgent(model, tools, sessions, new Gson()),
                tools,
                sessions,
                (actor, capabilities, id) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(id)),
                (actor, event) -> {},
                new Gson(),
                "system");
        UUID requestId = UUID.randomUUID();
        ServerAgentRequestPayload request = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION,
                requestId,
                "restored",
                "current question",
                true,
                List.of(
                        new ServerAgentHistoryMessage(
                                ServerAgentHistoryMessage.Role.USER, "old question"),
                        new ServerAgentHistoryMessage(
                                ServerAgentHistoryMessage.Role.ASSISTANT, "old answer")));

        service.ask(UUID.randomUUID(), request);

        assertEquals(
                List.of("USER:old question", "ASSISTANT:old answer", "USER:current question"),
                model.requests.get("restored").messages().stream()
                        .map(message -> message.role() + ":" + message.content().stream()
                                .map(content -> ((ModelContent.Text) content).text())
                                .reduce("", String::concat))
                        .toList());
    }

    @Test
    void disconnectCancelsEverySessionAndSuppressesLateEvents() {
        PendingModel model = new PendingModel();
        AgentSessionStore sessions = new AgentSessionStore();
        AgentToolExecutor tools = new EmptyTools();
        List<ServerAgentEventPayload> events = new ArrayList<>();
        ServerAgentService service = new ServerAgentService(
                new GameGuideAgent(model, tools, sessions, new Gson()), tools, sessions,
                (actor, capabilities, id) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(id)),
                (actor, event) -> events.add(event), new Gson(), "system");
        UUID actor = UUID.randomUUID();
        service.ask(actor, request(UUID.randomUUID(), "a"));
        service.ask(actor, request(UUID.randomUUID(), "b"));

        assertEquals(2, service.disconnect(actor));
        assertEquals(0, service.activeRequests());
        int before = events.size();
        model.pending.values().forEach(future -> future.complete(turn("late")));
        assertEquals(before, events.size());
    }

    @Test
    void waitsForEndpointReadinessBeforeCapturingServerContext() {
        PendingModel model = new PendingModel();
        AgentSessionStore sessions = new AgentSessionStore();
        AgentToolExecutor tools = new EmptyTools();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
        ServerAgentService service = new ServerAgentService(
                new GameGuideAgent(model, tools, sessions, new Gson()),
                tools,
                sessions,
                (actor, capabilities, id) -> {
                    captures.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ToolInvocationContext.developmentConsole(id));
                },
                (actor, event) -> {},
                new Gson(),
                "system",
                cancellation -> ready);

        service.ask(UUID.randomUUID(), request(UUID.randomUUID(), "fresh"));
        assertEquals(0, captures.get());
        ready.complete(null);
        assertEquals(1, captures.get());
    }

    @Test
    void cancelsWhileWaitingForEndpointWithoutCapturingContextLater() {
        PendingModel model = new PendingModel();
        AgentSessionStore sessions = new AgentSessionStore();
        AgentToolExecutor tools = new EmptyTools();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
        List<ServerAgentEventPayload> events = new ArrayList<>();
        ServerAgentService service = new ServerAgentService(
                new GameGuideAgent(model, tools, sessions, new Gson()), tools, sessions,
                (actor, capabilities, id) -> {
                    captures.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ToolInvocationContext.developmentConsole(id));
                },
                (actor, event) -> events.add(event),
                new Gson(), "system", cancellation -> ready);
        UUID actor = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        service.ask(actor, request(request, "waiting"));

        org.junit.jupiter.api.Assertions.assertTrue(service.cancel(actor, request));
        ready.complete(null);
        assertEquals(0, captures.get());
        assertEquals(0, service.activeRequests());
        assertEquals(1, events.stream().filter(ServerAgentEventPayload::terminal).count());
    }

    private static ServerAgentRequestPayload request(UUID id, String session) {
        return new ServerAgentRequestPayload(BridgeProtocol.VERSION, id, session, "question", true);
    }

    private static ModelTurn turn(String text) {
        return new ModelTurn("test", "test", List.of(new ModelContent.Text(text)),
                "end_turn", ModelUsage.empty());
    }

    private static final class PendingModel implements ModelClient {
        private final Map<String, CompletableFuture<ModelTurn>> pending = new java.util.HashMap<>();
        private final Map<String, ModelRequest> requests = new java.util.HashMap<>();
        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request, Consumer<ModelEvent> events, CancellationSignal cancellation) {
            String session = request.sessionKey().substring(request.sessionKey().lastIndexOf(':') + 1);
            requests.put(session, request);
            CompletableFuture<ModelTurn> future = new CompletableFuture<>();
            pending.put(session, future);
            cancellation.onCancel(() -> future.completeExceptionally(new dev.openallay.model.ModelClientException(
                    new dev.openallay.model.ModelFailure("agent_cancelled", "cancelled", null))));
            return future;
        }
    }

    private static final class EmptyTools implements AgentToolExecutor {
        @Override public List<ModelToolDefinition> definitions() { return List.of(); }
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override
        public CompletableFuture<AgentToolResult> execute(
                String name, com.google.gson.JsonObject arguments,
                ToolInvocationContext context, CancellationSignal cancellation) {
            return CompletableFuture.failedFuture(new AssertionError("No tool expected"));
        }
    }
}
