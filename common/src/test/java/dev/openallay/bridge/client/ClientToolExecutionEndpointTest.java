package dev.openallay.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.openallay.agent.tool.ToolRuntimeCatalog;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.ClientToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ClientToolExecutionEndpointTest {
    @Test
    void freezesOneRequestCatalogAndReturnsACompleteNormalizedResult() {
        ToolRegistry registry = registry();
        List<ClientToolResultChunkPayload> sent = new ArrayList<>();
        ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                (capabilities, correlation) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(correlation)),
                sent::add,
                new Gson(),
                4,
                Runnable::run);
        UUID requestId = UUID.randomUUID();
        ToolResult<ClientToolExecutionEndpoint.OpenedRequest> opened = endpoint.open(
                requestId, "main", ToolRuntimeCatalog.from(registry.registrations(), java.util.Set.of()));
        @SuppressWarnings("unchecked")
        ToolResult.Success<ClientToolExecutionEndpoint.OpenedRequest> success =
                (ToolResult.Success<ClientToolExecutionEndpoint.OpenedRequest>)
                        assertInstanceOf(ToolResult.Success.class, opened);
        assertEquals(List.of("test:fact"), success.value().clientToolIds());

        UUID invocation = UUID.randomUUID();
        assertInstanceOf(ToolResult.Success.class, endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                invocation,
                "main",
                "test:fact",
                "{\"value\":42}")));

        String normalized = reassemble(sent);
        assertEquals(
                42,
                JsonParser.parseString(normalized)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .get("value")
                        .getAsInt());
        assertEquals(requestId, sent.getFirst().requestId());
        assertEquals(invocation, sent.getFirst().invocationId());
    }

    @Test
    void rejectsMismatchedOrClosedRequestsWithStructuredResults() {
        ToolRegistry registry = registry();
        List<ClientToolResultChunkPayload> sent = new ArrayList<>();
        ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                (capabilities, correlation) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(correlation)),
                sent::add,
                new Gson(),
                128,
                Runnable::run);
        UUID requestId = UUID.randomUUID();
        endpoint.open(
                requestId, "main", ToolRuntimeCatalog.from(registry.registrations(), java.util.Set.of()));

        endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                UUID.randomUUID(),
                "other",
                "test:fact",
                "{\"value\":1}"));
        assertTrue(reassemble(sent).contains("client_tool_rejected"));
        sent.clear();
        endpoint.close(requestId);
        endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                UUID.randomUUID(),
                "main",
                "test:fact",
                "{\"value\":1}"));
        assertTrue(reassemble(sent).contains("client_tool_unavailable"));
    }

    @Test
    void cancellationSuppressesLateContextCompletion() {
        ToolRegistry registry = registry();
        CompletableFuture<ToolInvocationContext> context = new CompletableFuture<>();
        List<ClientToolResultChunkPayload> sent = new ArrayList<>();
        ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                (capabilities, correlation) -> context,
                sent::add,
                new Gson(),
                128,
                Runnable::run);
        UUID requestId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        endpoint.open(
                requestId, "main", ToolRuntimeCatalog.from(registry.registrations(), java.util.Set.of()));
        endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                invocationId,
                "main",
                "test:fact",
                "{\"value\":7}"));

        assertTrue(endpoint.cancel(new ClientToolCancelPayload(
                BridgeProtocol.VERSION, requestId, invocationId)));
        context.complete(ToolInvocationContext.developmentConsole(invocationId.toString()));
        assertTrue(sent.isEmpty());
        assertFalse(endpoint.cancel(new ClientToolCancelPayload(
                BridgeProtocol.VERSION, requestId, invocationId)));
    }

    @Test
    void executesAndEncodesAfterCaptureOnTheDedicatedWorker() throws Exception {
        ToolRegistry registry = registry();
        CompletableFuture<ToolInvocationContext> context = new CompletableFuture<>();
        CompletableFuture<String> responseThread = new CompletableFuture<>();
        try (var worker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "client-tool-test-worker"))) {
            ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                    (capabilities, correlation) -> context,
                    chunk -> responseThread.complete(Thread.currentThread().getName()),
                    new Gson(),
                    128,
                    worker);
            UUID requestId = UUID.randomUUID();
            UUID invocationId = UUID.randomUUID();
            endpoint.open(requestId, "main", ToolRuntimeCatalog.from(
                    registry.registrations(), java.util.Set.of()));
            endpoint.handle(new ClientToolCallPayload(
                    BridgeProtocol.VERSION,
                    requestId,
                    invocationId,
                    "main",
                    "test:fact",
                    "{\"value\":7}"));

            context.complete(ToolInvocationContext.developmentConsole(invocationId.toString()));

            assertEquals("client-tool-test-worker", responseThread.get(5, TimeUnit.SECONDS));
        }
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new FactTool()));
        return registry;
    }

    private static String reassemble(List<ClientToolResultChunkPayload> chunks) {
        ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
        java.util.Optional<String> complete = java.util.Optional.empty();
        for (ClientToolResultChunkPayload chunk : chunks) {
            java.util.Optional<String> accepted = reassembler.accept(chunk.asRemoteChunk());
            if (accepted.isPresent()) complete = accepted;
        }
        return complete.orElseThrow();
    }

    private static final class FactTool implements Tool<FactTool.Input, FactTool.Output> {
        record Input(int value) {}
        record Output(int value) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "test:fact", "Return a fact", Input.class, Output.class, ToolAccess.READ_ONLY);

        @Override
        public ToolDescriptor<Input, Output> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output(input.value()));
        }
    }
}
