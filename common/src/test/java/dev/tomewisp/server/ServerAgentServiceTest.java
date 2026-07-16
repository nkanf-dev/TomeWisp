package dev.tomewisp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.Gson;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.tool.ToolResult;
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

    private static ServerAgentRequestPayload request(UUID id, String session) {
        return new ServerAgentRequestPayload(BridgeProtocol.VERSION, id, session, "question", true);
    }

    private static ModelTurn turn(String text) {
        return new ModelTurn("test", "test", List.of(new ModelContent.Text(text)),
                "end_turn", ModelUsage.empty());
    }

    private static final class PendingModel implements ModelClient {
        private final Map<String, CompletableFuture<ModelTurn>> pending = new java.util.HashMap<>();
        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request, Consumer<ModelEvent> events, CancellationSignal cancellation) {
            String session = request.sessionKey().substring(request.sessionKey().lastIndexOf(':') + 1);
            CompletableFuture<ModelTurn> future = new CompletableFuture<>();
            pending.put(session, future);
            cancellation.onCancel(() -> future.completeExceptionally(new dev.tomewisp.model.ModelClientException(
                    new dev.tomewisp.model.ModelFailure("agent_cancelled", "cancelled", null))));
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
