package dev.openallay.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.ClientToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.bridge.server.PlayerClientToolRouter;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
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
                calls.getFirst().payload().viewId(),
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
    void republishesClientResourceTruthIntoTheServerModelOwnersResultMount() {
        ResourceRequestRegistry resources = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry());
        ToolRegistry registry = new ToolRegistry();
        registry.registerResourceTools("test:vfs", resources);
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry,
                new Gson(),
                transport(calls, new ArrayList<>()),
                Duration.ofMinutes(1),
                resources);
        UUID actor = GroundedTestFixtures.PLAYER_ID;
        UUID requestId = UUID.randomUUID();
        ToolInvocationContext fixture = GroundedTestFixtures.fullContext();
        ToolInvocationContext context = new ToolInvocationContext(
                requestId.toString(), fixture.capturedAt(), fixture.caller(), fixture.player(),
                fixture.registries(), fixture.recipes(), fixture.observableGameState(), fixture.metrics());
        try (var handle = resources.open(
                actor,
                "main",
                requestId,
                13,
                "server",
                Set.of("openallay:resource_read"),
                new ContextBudget(100_000, 4_096),
                context)) {
            AgentToolExecutor tools = success(router.open(
                    actor,
                    requestId,
                    "main",
                    List.of("openallay:resource_read")));
            JsonObject input = JsonParser.parseString(
                    "{\"paths\":[\"/game/runtime\"]}").getAsJsonObject();
            CompletableFuture<AgentToolResult> result = tools.execute(
                    "resource_read", input, context, new CancellationSignal());

            ClientToolCallPayload call = calls.getFirst().payload();
            String normalized = """
                    {
                      "status":"success",
                      "outputType":"dev.openallay.tool.resource.ResourceToolOutput",
                      "value":{
                        "operation":"resource_read",
                        "resultPath":"/result/client-copy",
                        "items":[{
                          "inputIndex":0,
                          "input":"/game/runtime",
                          "status":"success",
                          "value":{
                            "path":"/game/runtime",
                            "kind":"record",
                            "presentation":"diagnostics",
                            "presentationReferences":{},
                            "value":{"loader":"fabric"}
                          }
                        }],
                        "evidence":[{
                          "authority":"CLIENT_VISIBLE",
                          "completeness":"COMPLETE",
                          "capturedAt":"1970-01-01T00:00:00Z",
                          "sourceId":"minecraft:client_state",
                          "provenance":"minecraft:client_state",
                          "gameVersion":"26.2",
                          "loader":"fabric",
                          "details":{}
                        }]
                      }
                    }
                    """;
            for (var chunk : new ResultChunker().split(
                    call.invocationId(), call.viewId(), normalized, 17)) {
                assertTrue(router.receive(
                        actor, ClientToolResultChunkPayload.from(requestId, chunk)));
            }

            AgentToolResult completed = result.join();
            assertFalse(completed.failure());
            assertTrue(completed.uiReference().resultPath().toString().startsWith("/result/"));
            assertEquals(completed.uiReference().resultPath(),
                    completed.modelView().receipts().getFirst().resultPath());
            assertEquals("client_visible", completed.modelView().receipts().getFirst().authority());
            assertTrue(resources.capture(context, new CancellationSignal()).view()
                    .require(completed.uiReference().resultPath()) != null);
            assertTrue(router.close(actor, requestId));
        }
        resources.close();
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
                                call.invocationId(), call.viewId(), normalized.toString(), 128)
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
                                        calls.getFirst().payload().viewId(),
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
        registry.register("resource", List.of(new ResourceReadTool()));
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor,
                requestId,
                "main",
                List.of("openallay:resource_read")));
        JsonObject input = paths("/world/dimension");

        AgentToolResult result = tools.execute(
                        "openallay:resource_read",
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
    void playerVisibleGameStatePlacementRoutesToTheRequestingClient() {
        ToolRegistry registry = registry();
        registry.register("resource", List.of(new ResourceReadTool()));
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor,
                requestId,
                "main",
                List.of("openallay:resource_read")));
        JsonObject input = paths("/game/options");

        CompletableFuture<AgentToolResult> result = tools.execute(
                "openallay:resource_read",
                input,
                ToolInvocationContext.developmentConsole(requestId.toString()),
                new CancellationSignal());

        assertEquals(1, calls.size());
        assertEquals(actor, calls.getFirst().actorId());
        assertEquals("openallay:resource_read", calls.getFirst().payload().toolId());
        assertTrue(router.close(actor, requestId));
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void playerResourcePlacementRoutesToTheRequestingClient() {
        ToolRegistry registry = registry();
        registry.register("resource", List.of(new ResourceReadTool()));
        List<SentCall> calls = new ArrayList<>();
        PlayerClientToolRouter router = new PlayerClientToolRouter(
                registry, new Gson(), transport(calls, new ArrayList<>()));
        UUID actor = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentToolExecutor tools = success(router.open(
                actor,
                requestId,
                "main",
                List.of("openallay:resource_read")));
        JsonObject input = paths("/player/inventory");

        CompletableFuture<AgentToolResult> result = tools.execute(
                "openallay:resource_read",
                input,
                ToolInvocationContext.developmentConsole(requestId.toString()),
                new CancellationSignal());

        assertEquals(1, calls.size());
        assertEquals(actor, calls.getFirst().actorId());
        assertEquals("openallay:resource_read", calls.getFirst().payload().toolId());
        assertTrue(router.close(actor, requestId));
        assertTrue(result.isCompletedExceptionally());
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
                            .split(
                                    call.invocationId(),
                                    call.viewId(),
                                    "{\"status\":\"failure\"}",
                                    1)
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

    private static JsonObject paths(String... values) {
        JsonObject result = new JsonObject();
        com.google.gson.JsonArray paths = new com.google.gson.JsonArray();
        for (String value : values) paths.add(value);
        result.add("paths", paths);
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

    private static final class ResourceReadTool implements Tool<ResourceReadTool.Input, ResourceReadTool.Output> {
        record Input(List<String> paths) {}
        record Output(String placement) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "openallay:resource_read",
                "Read resource paths",
                Input.class,
                Output.class,
                ToolAccess.READ_ONLY);

        @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output("server"));
        }
    }

    private static final class TestPlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
        @Override public String gameVersion() { return "26.2"; }
        @Override public List<InstalledModMetadata> installedMods() { return List.of(); }
        @Override public ModResourceSnapshot captureModResources() {
            return ModResourceSnapshot.unavailable(Instant.EPOCH, "fixture");
        }
    }

    private record SentCall(UUID actorId, ClientToolCallPayload payload) {}
    private record SentCancel(UUID actorId, ClientToolCancelPayload payload) {}
}
