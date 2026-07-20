package dev.openallay.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.RemoteToolCallPayload;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.CancellationSignal;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.testing.GroundedTestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RemoteToolExecutorAliasTest {
    @Test
    void resolvesEncodedAndCanonicalAliasesOnlyWithinTheServerPrefix() {
        RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
        capabilities.replace(new CapabilityPayload(
                BridgeProtocol.VERSION,
                List.of(new CapabilityPayload.RemoteToolCapability(
                        "openallay:inspect_game_state",
                        "Inspect game state",
                        "{\"type\":\"object\"}")),
                false,
                0,
                0,
                0,
                ""));
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities,
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });

        assertEquals(
                "openallay:inspect_game_state",
                executor.canonicalToolId("server__openallay__inspect_game_state").orElseThrow());
        assertEquals(
                "openallay:inspect_game_state",
                executor.canonicalToolId("server__openallay:inspect_game_state").orElseThrow());
        assertEquals(
                "openallay:inspect_game_state",
                executor.canonicalToolId("server__OpenAllay:Inspect_Game_State").orElseThrow());
        assertTrue(executor.canonicalToolId("openallay:inspect_game_state").isEmpty());
        assertTrue(executor.canonicalToolId("server__openallay:invented").isEmpty());

        executor.execute(
                "server__openallay:inspect_game_state",
                new JsonObject(),
                ToolInvocationContext.developmentConsole("remote-alias"),
                new CancellationSignal());
        assertEquals("openallay:inspect_game_state", sent.get().toolId());
    }

    @Test
    void cancellationBeforeDispatchDoesNotSendAStaleCall() {
        RemoteCapabilityStore capabilities = capabilities();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities,
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { calls.incrementAndGet(); }
                    @Override public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {
                        cancels.incrementAndGet();
                    }
                });
        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        var result = executor.execute(
                "server__openallay:inspect_game_state",
                new JsonObject(),
                ToolInvocationContext.developmentConsole("remote-cancel"),
                cancellation);

        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, calls.get());
        assertEquals(0, cancels.get());
    }

    @Test
    void transportFailureBecomesAModelVisibleToolFailure() throws Exception {
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities(),
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) {
                        throw new IllegalStateException("offline");
                    }
                    @Override public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });

        var result = executor.execute(
                        "server__openallay:inspect_game_state",
                        new JsonObject(),
                        ToolInvocationContext.developmentConsole("remote-offline"),
                        new CancellationSignal())
                .get(1, TimeUnit.SECONDS);

        assertTrue(result.failure());
        assertEquals("server_tool_bridge_unavailable", result.normalized().get("code").getAsString());
    }

    @Test
    void staleResourceViewChunkFailsClosedBeforeTruthProjection() {
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities(),
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(
                            dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });
        var result = executor.execute(
                "server__openallay:inspect_game_state",
                new JsonObject(),
                ToolInvocationContext.developmentConsole("stale-view"),
                new CancellationSignal());
        RemoteToolCallPayload call = sent.get();
        String normalized = "{\"status\":\"failure\",\"code\":\"x\",\"message\":\"x\"}";
        var wrong = new dev.openallay.bridge.protocol.ResultChunker().split(
                call.correlationId(),
                dev.openallay.bridge.protocol.BridgeViewIdentity.forOperation(UUID.randomUUID()),
                normalized,
                128).getFirst();

        assertFalse(executor.receive(wrong));
        assertTrue(result.join().failure());
        assertEquals("server_tool_result_invalid", result.join().normalized().get("code").getAsString());
    }

    @Test
    void validatesAndHashesExactTruthBeforeProjectingAtTheAgentOwner() {
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities(),
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(
                            dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });
        var result = executor.execute(
                "server__openallay:inspect_game_state",
                new JsonObject(),
                ToolInvocationContext.developmentConsole("truth-view"),
                new CancellationSignal());
        JsonObject exact = new JsonObject();
        exact.addProperty("status", "success");
        exact.addProperty("outputType", "example.ExactTruth");
        JsonObject value = new JsonObject();
        value.addProperty("path", "/world/dimension");
        value.addProperty("generation", "server-generation-7");
        exact.add("value", value);
        RemoteToolCallPayload call = sent.get();
        new dev.openallay.bridge.protocol.ResultChunker().split(
                        call.correlationId(), call.viewId(), exact.toString(), 3)
                .forEach(executor::receive);

        var completed = result.join();
        assertEquals(exact, completed.normalized());
        assertTrue(completed.modelView().text().contains("/world/dimension"));
        assertFalse(completed.modelView().text().contains("{\"status\""));
    }

    @Test
    void republishesServerResourceTruthIntoTheClientModelOwnersResultMount() {
        RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
        capabilities.replace(new CapabilityPayload(
                BridgeProtocol.VERSION,
                List.of(new CapabilityPayload.RemoteToolCapability(
                        "openallay:resource_read",
                        "Read a server resource",
                        "{\"type\":\"object\"}")),
                false,
                0,
                0,
                0,
                ""));
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities,
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(
                            dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });
        ResourceRequestRegistry resources = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry());
        executor.configureResources(resources);
        UUID requestId = UUID.randomUUID();
        ToolInvocationContext fixture = GroundedTestFixtures.fullContext();
        ToolInvocationContext context = new ToolInvocationContext(
                requestId.toString(), fixture.capturedAt(), fixture.caller(), fixture.player(),
                fixture.registries(), fixture.recipes(), fixture.observableGameState(), fixture.metrics());
        try (var handle = resources.open(
                GroundedTestFixtures.PLAYER_ID,
                "main",
                requestId,
                17,
                "client",
                Set.of("openallay:resource_read"),
                new ContextBudget(100_000, 4_096),
                context)) {
            JsonObject input = JsonParser.parseString(
                    "{\"paths\":[\"/world/dimension\"]}").getAsJsonObject();
            var result = executor.execute(
                    "server__openallay:resource_read",
                    input,
                    context,
                    new CancellationSignal());
            RemoteToolCallPayload call = sent.get();
            String normalized = """
                    {
                      "status":"success",
                      "outputType":"dev.openallay.tool.resource.ResourceToolOutput",
                      "value":{
                        "operation":"resource_read",
                        "resultPath":"/result/server-copy",
                        "items":[{
                          "inputIndex":0,
                          "input":"/world/dimension",
                          "status":"success",
                          "value":{"path":"/world/dimension","kind":"record","value":{"id":"minecraft:overworld"}}
                        }],
                        "evidence":[{
                          "authority":"SERVER_AUTHORITATIVE",
                          "completeness":"COMPLETE",
                          "capturedAt":"1970-01-01T00:00:00Z",
                          "sourceId":"minecraft:server_level",
                          "provenance":"minecraft:server_level",
                          "gameVersion":"26.2",
                          "loader":"fabric",
                          "details":{}
                        }]
                      }
                    }
                    """;
            new dev.openallay.bridge.protocol.ResultChunker().split(
                            call.correlationId(), call.viewId(), normalized, 19)
                    .forEach(executor::receive);

            var completed = result.join();
            assertFalse(completed.failure());
            assertEquals("server_authoritative",
                    completed.modelView().receipts().getFirst().authority());
            assertEquals(completed.uiReference().resultPath(),
                    completed.modelView().receipts().getFirst().resultPath());
            assertTrue(resources.capture(context, new CancellationSignal()).view()
                    .require(completed.uiReference().resultPath()) != null);
        }
        resources.close();
    }

    @Test
    void missingResultTimesOutAsAToolFailureAndSendsCancel() throws Exception {
        AtomicInteger cancels = new AtomicInteger();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities(),
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) {}
                    @Override public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {
                        cancels.incrementAndGet();
                    }
                },
                Duration.ofMillis(20));

        var result = executor.execute(
                        "server__openallay:inspect_game_state",
                        new JsonObject(),
                        ToolInvocationContext.developmentConsole("remote-timeout"),
                        new CancellationSignal())
                .get(2, TimeUnit.SECONDS);

        assertTrue(result.failure());
        assertEquals("server_tool_timeout", result.normalized().get("code").getAsString());
        assertEquals(1, cancels.get());
    }

    @Test
    void cancellationRacingALateChunkCannotRecreatePartialAssembly() {
        AtomicReference<RemoteToolCallPayload> call = new AtomicReference<>();
        RemoteToolExecutor executor = new RemoteToolExecutor(
                capabilities(),
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { call.set(payload); }
                    @Override public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });

        for (int attempt = 0; attempt < 100; attempt++) {
            CancellationSignal cancellation = new CancellationSignal();
            executor.execute(
                    "server__openallay:inspect_game_state",
                    new JsonObject(),
                    ToolInvocationContext.developmentConsole("remote-race-" + attempt),
                    cancellation);
            RemoteToolCallPayload payload = call.get();
            var firstChunk = new dev.openallay.bridge.protocol.ResultChunker()
                    .split(
                            payload.correlationId(),
                            payload.viewId(),
                            "{\"status\":\"failure\"}",
                            1)
                    .getFirst();

            CompletableFuture.allOf(
                            CompletableFuture.runAsync(cancellation::cancel),
                            CompletableFuture.runAsync(() -> executor.receive(firstChunk)))
                    .join();

            assertEquals(0, executor.activeResultAssemblies());
        }
    }

    private static RemoteCapabilityStore capabilities() {
        RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
        capabilities.replace(new CapabilityPayload(
                BridgeProtocol.VERSION,
                List.of(new CapabilityPayload.RemoteToolCapability(
                        "openallay:inspect_game_state",
                        "Inspect game state",
                        "{\"type\":\"object\"}")),
                false,
                0,
                0,
                0,
                ""));
        return capabilities;
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
}
