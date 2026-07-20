package dev.openallay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.ClientToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.bridge.protocol.ServerAgentEventPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestPayload;
import dev.openallay.bridge.server.PlayerClientToolRouter;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ServerClientToolAgentTest {
    @Test
    void remoteClientToolFailureReturnsToTheModelAndTheAgentCompletes() {
        Gson gson = new Gson();
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new FactTool()));
        AtomicReference<PlayerClientToolRouter> routerRef = new AtomicReference<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry,
                gson,
                new PlayerClientToolRouter.Transport() {
                    @Override
                    public boolean call(UUID actorId, ClientToolCallPayload payload) {
                        JsonObject failure = new JsonObject();
                        failure.addProperty("status", "failure");
                        failure.addProperty("code", "client_tool_unavailable");
                        failure.addProperty("message", "unavailable");
                        for (var chunk : new ResultChunker().split(
                                payload.invocationId(), payload.viewId(), failure.toString(), 5)) {
                            routerRef.get().receive(
                                    actorId,
                                    ClientToolResultChunkPayload.from(payload.requestId(), chunk));
                        }
                        return true;
                    }

                    @Override
                    public void cancel(UUID actorId, ClientToolCancelPayload payload) {}
                });
        routerRef.set(router);
        AgentSessionStore sessions = new AgentSessionStore();
        RecoveringModel model = new RecoveringModel();
        List<ServerAgentEventPayload> events = new ArrayList<>();
        ServerAgentService service = new ServerAgentService(
                (actor, payload) -> {
                    ToolResult<AgentToolExecutor> opened = router.open(
                            actor,
                            payload.requestId(),
                            payload.sessionId(),
                            payload.clientToolIds());
                    if (opened instanceof ToolResult.Failure<AgentToolExecutor> failure) {
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    AgentToolExecutor tools = ((ToolResult.Success<AgentToolExecutor>) opened).value();
                    return new ToolResult.Success<>(new ServerAgentService.RequestRuntime(
                            new GameGuideAgent(model, tools, sessions, gson),
                            tools,
                            () -> router.close(actor, payload.requestId())));
                },
                sessions,
                (actor, capabilities, correlation) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(correlation)),
                (actor, event) -> events.add(event),
                gson,
                "system",
                cancellation -> CompletableFuture.completedFuture(null));
        UUID requestId = UUID.randomUUID();

        ToolResult<ServerAgentService.Accepted> accepted = service.ask(
                UUID.randomUUID(),
                new ServerAgentRequestPayload(
                        BridgeProtocol.VERSION,
                        requestId,
                        "main",
                        "question",
                        true,
                        List.of(),
                        List.of("test:fact")));

        assertInstanceOf(ToolResult.Success.class, accepted);
        assertEquals(2, model.calls.get());
        assertTrue(model.observedFailureResult);
        assertEquals(0, service.activeRequests());
        assertEquals(0, router.activeRequests());
        assertTrue(events.stream().anyMatch(event -> event.terminal()
                && event.eventJson().contains("recovered answer")));
    }

    private static final class RecoveringModel implements ModelClient {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean observedFailureResult;

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            if (calls.getAndIncrement() == 0) {
                JsonObject input = new JsonObject();
                input.addProperty("value", 1);
                return CompletableFuture.completedFuture(new ModelTurn(
                        "test",
                        "test",
                        List.of(new ModelContent.ToolUse("call-1", "test__fact", input)),
                        "tool_use",
                        ModelUsage.empty()));
            }
            ModelMessage resultMessage = request.messages().getLast();
            ModelContent.ToolResult result = assertInstanceOf(
                    ModelContent.ToolResult.class, resultMessage.content().getFirst());
            observedFailureResult = result.error()
                    && result.text().contains("code: client_tool_unavailable");
            return CompletableFuture.completedFuture(new ModelTurn(
                    "test",
                    "test",
                    List.of(new ModelContent.Text("recovered answer")),
                    "end_turn",
                    ModelUsage.empty()));
        }
    }

    private static final class FactTool implements Tool<FactTool.Input, FactTool.Output> {
        record Input(int value) {}
        record Output(int value) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "test:fact", "Return a fact", Input.class, Output.class, ToolAccess.READ_ONLY);

        @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output(input.value()));
        }
    }
}
