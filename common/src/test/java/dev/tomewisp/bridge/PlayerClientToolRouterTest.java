package dev.tomewisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.ClientToolCallPayload;
import dev.tomewisp.bridge.protocol.ClientToolCancelPayload;
import dev.tomewisp.bridge.protocol.ClientToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ResultChunker;
import dev.tomewisp.bridge.server.PlayerClientToolRouter;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class PlayerClientToolRouterTest {
    @Test
    void intersectsTrustedIdsAndRoutesTheFrozenClientToolToItsActor() {
        ToolRegistry registry = registry();
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry,
                new Gson(),
                transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor,
                requestId,
                "main",
                List.of("test:fact", "malicious:invented")));

        CompletableFuture<AgentToolResult> result = tools.execute(
                "test:fact",
                arguments("value", 4),
                ToolInvocationContext.developmentConsole(requestId.toString()),
                new CancellationSignal());
        assertEquals(1, calls.size());
        assertEquals(actor, calls.getFirst().actorId());
        assertEquals(requestId, calls.getFirst().payload().requestId());
        assertEquals("test:fact", calls.getFirst().payload().toolId());

        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.addProperty("outputType", FactTool.Output.class.getName());
        JsonObject value = new JsonObject();
        value.addProperty("value", 99);
        normalized.add("value", value);
        for (var chunk : new ResultChunker().split(
                calls.getFirst().payload().invocationId(),
                normalized.toString(),
                3)) {
            assertTrue(router.receive(
                    actor, ClientToolResultChunkPayload.from(requestId, chunk)));
        }

        AgentToolResult completed = result.join();
        assertFalse(completed.failure());
        assertEquals("test:fact", completed.toolId());
        assertEquals(99, completed.normalized().getAsJsonObject("value").get("value").getAsInt());
    }

    @Test
    void wrongActorCannotCompleteAndRemoteFailureRemainsAToolResult() {
        ToolRegistry registry = registry();
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor, requestId, "main", List.of("test:fact")));
        CompletableFuture<AgentToolResult> result = tools.execute(
                "test__fact",
                arguments("value", 1),
                ToolInvocationContext.developmentConsole(requestId.toString()),
                new CancellationSignal());
        ClientToolCallPayload call = calls.getFirst().payload();
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "failure");
        normalized.addProperty("code", "client_tool_unavailable");
        normalized.addProperty("message", "unavailable");
        var chunk = ClientToolResultChunkPayload.from(
                requestId,
                new ResultChunker().split(
                                call.invocationId(), normalized.toString(), 128)
                        .getFirst());

        assertFalse(router.receive(UUID.randomUUID(), chunk));
        assertFalse(result.isDone());
        assertTrue(router.receive(actor, chunk));
        AgentToolResult completed = result.join();
        assertTrue(completed.failure());
        assertEquals("client_tool_unavailable", completed.normalized().get("code").getAsString());
    }

    @Test
    void requestCancellationCancelsOnlyItsInvocationAndSuppressesLateChunks() {
        ToolRegistry registry = registry();
        List<SentCall> calls = new ArrayList<>();
        List<SentCancel> cancels = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, cancels));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor, requestId, "main", List.of("test:fact")));
        CancellationSignal cancellation = new CancellationSignal();
        CompletableFuture<AgentToolResult> result = tools.execute(
                "test:fact",
                arguments("value", 1),
                ToolInvocationContext.developmentConsole(requestId.toString()),
                cancellation);

        assertTrue(cancellation.cancel());
        assertEquals(1, cancels.size());
        assertEquals(requestId, cancels.getFirst().payload().requestId());
        assertTrue(result.isCompletedExceptionally());
        JsonObject late = new JsonObject();
        late.addProperty("status", "failure");
        late.addProperty("code", "late");
        late.addProperty("message", "late");
        assertFalse(router.receive(
                actor,
                ClientToolResultChunkPayload.from(
                        requestId,
                        new ResultChunker().split(
                                        calls.getFirst().payload().invocationId(),
                                        late.toString(),
                                        128)
                                .getFirst())));
    }

    @Test
    void alreadyCancelledRequestNeverDispatchesAClientToolCall() {
        List<SentCall> calls = new ArrayList<>();
        List<SentCancel> cancels = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry(), new Gson(), transport(calls, cancels));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor, requestId, "main", List.of("test:fact")));
        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        CompletableFuture<AgentToolResult> result = tools.execute(
                "test:fact",
                arguments("value", 1),
                ToolInvocationContext.developmentConsole(requestId.toString()),
                cancellation);

        assertTrue(result.isCompletedExceptionally());
        assertTrue(calls.isEmpty());
        assertTrue(cancels.isEmpty());
    }

    @Test
    void serverAuthoritativeWorldQueryPlacementStaysLocalWithoutFallback() {
        ToolRegistry registry = registry();
        registry.register("game", List.of(new InspectTool()));
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor,
                requestId,
                "main",
                List.of("tomewisp:inspect_game_state")));
        JsonObject input = new JsonObject();
        input.addProperty("section", "WORLD_QUERY");

        AgentToolResult result = tools.execute(
                        "tomewisp:inspect_game_state",
                        input,
                        ToolInvocationContext.developmentConsole(requestId.toString()),
                        new CancellationSignal())
                .join();

        assertTrue(calls.isEmpty());
        assertFalse(result.failure());
        assertEquals(
                "server",
                result.normalized().getAsJsonObject("value").get("placement").getAsString());
    }

    @Test
    void lostClientResultBecomesAToolFailureInsteadOfHangingTheAgent() throws Exception {
        List<SentCall> calls = new ArrayList<>();
        List<SentCancel> cancels = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry(),
                new Gson(),
                transport(calls, cancels),
                Duration.ofMillis(10));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor, requestId, "main", List.of("test:fact")));

        AgentToolResult result = tools.execute(
                        "test__fact",
                        arguments("value", 1),
                        ToolInvocationContext.developmentConsole(requestId.toString()),
                        new CancellationSignal())
                .get(1, TimeUnit.SECONDS);

        assertTrue(result.failure());
        assertEquals("client_tool_timeout", result.normalized().get("code").getAsString());
        assertEquals(1, calls.size());
        assertEquals(1, cancels.size());
    }

    @Test
    void cancellationRacingALateChunkCannotRecreatePartialAssembly() {
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry(), new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor, requestId, "main", List.of("test:fact")));

        for (int attempt = 0; attempt < 100; attempt++) {
            CancellationSignal cancellation = new CancellationSignal();
            tools.execute(
                    "test:fact",
                    arguments("value", attempt),
                    ToolInvocationContext.developmentConsole("client-race-" + attempt),
                    cancellation);
            ClientToolCallPayload call = calls.getLast().payload();
            ClientToolResultChunkPayload firstChunk = ClientToolResultChunkPayload.from(
                    requestId,
                    new ResultChunker()
                            .split(call.invocationId(), "{\"status\":\"failure\"}", 1)
                            .getFirst());

            CompletableFuture.allOf(
                            CompletableFuture.runAsync(cancellation::cancel),
                            CompletableFuture.runAsync(() -> router.receive(actor, firstChunk)))
                    .join();

            assertEquals(0, router.activeResultAssemblies(actor, requestId));
        }
    }

    private static PlayerClientToolRouter.Transport transport(
            List<SentCall> calls, List<SentCancel> cancels) {
        return new PlayerClientToolRouter.Transport() {
            @Override
            public boolean call(UUID actorId, ClientToolCallPayload payload) {
                calls.add(new SentCall(actorId, payload));
                return true;
            }

            @Override
            public void cancel(UUID actorId, ClientToolCancelPayload payload) {
                cancels.add(new SentCancel(actorId, payload));
            }
        };
    }

    private static AgentToolExecutor success(ToolResult<AgentToolExecutor> result) {
        return ((ToolResult.Success<AgentToolExecutor>) assertInstanceOf(
                        ToolResult.Success.class, result))
                .value();
    }

    private static JsonObject arguments(String key, int value) {
        JsonObject result = new JsonObject();
        result.addProperty(key, value);
        return result;
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new FactTool()));
        return registry;
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

    private static final class InspectTool implements Tool<InspectTool.Input, InspectTool.Output> {
        record Input(String section) {}
        record Output(String placement) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "tomewisp:inspect_game_state",
                "Inspect game state",
                Input.class,
                Output.class,
                ToolAccess.READ_ONLY);

        @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output("server"));
        }
    }

    private record SentCall(UUID actorId, ClientToolCallPayload payload) {}
    private record SentCancel(UUID actorId, ClientToolCancelPayload payload) {}
}
