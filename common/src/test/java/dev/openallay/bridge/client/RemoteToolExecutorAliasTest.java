package dev.openallay.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.RemoteToolCallPayload;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import java.time.Duration;
import java.util.List;
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
                    .split(payload.correlationId(), "{\"status\":\"failure\"}", 1)
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
}
