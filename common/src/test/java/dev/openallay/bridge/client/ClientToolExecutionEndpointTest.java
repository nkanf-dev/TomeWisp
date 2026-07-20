package dev.openallay.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.openallay.agent.tool.ToolRuntimeCatalog;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.BridgeViewIdentity;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.ClientToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.testing.GroundedTestFixtures;
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
import java.time.Instant;
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
    void rejectsAStaleRequestViewBeforeCapturingClientState() {
        ToolRegistry registry = registry();
        List<ClientToolResultChunkPayload> sent = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
        ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                (capabilities, correlation) -> {
                    captures.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ToolInvocationContext.developmentConsole(correlation));
                },
                sent::add,
                new Gson(),
                128,
                Runnable::run);
        UUID requestId = UUID.randomUUID();
        endpoint.open(
                requestId, "main", ToolRuntimeCatalog.from(registry.registrations(), java.util.Set.of()));

        ToolResult<?> result = endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                UUID.randomUUID(),
                "main",
                "test:fact",
                BridgeViewIdentity.forRequest(
                        UUID.randomUUID(), "main", BridgeViewIdentity.Owner.CLIENT),
                "{\"value\":1}"));

        @SuppressWarnings("unchecked")
        ToolResult.Failure<Object> failure = (ToolResult.Failure<Object>) assertInstanceOf(
                ToolResult.Failure.class, result);
        assertEquals("stale_resource", failure.code());
        assertEquals(0, captures.get());
        assertTrue(reassemble(sent).contains("stale_resource"));
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

    @Test
    void capturesOneFrozenClientSnapshotAndKeepsOneResourceViewForTheWholeServerRequest() {
        ResourceRequestRegistry resources = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry());
        ToolRegistry registry = new ToolRegistry();
        registry.registerResourceTools("test:vfs", resources);
        List<ClientToolResultChunkPayload> sent = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
        ClientToolExecutionEndpoint endpoint = new ClientToolExecutionEndpoint(
                (capabilities, correlation) -> {
                    captures.incrementAndGet();
                    ToolInvocationContext fixture = GroundedTestFixtures.fullContext();
                    return CompletableFuture.completedFuture(new ToolInvocationContext(
                            correlation,
                            fixture.capturedAt(),
                            fixture.caller(),
                            fixture.player(),
                            fixture.registries(),
                            fixture.recipes(),
                            fixture.observableGameState(),
                            fixture.metrics()));
                },
                sent::add,
                new Gson(),
                512,
                resources,
                () -> new ContextBudget(100_000, 4_096),
                Runnable::run);
        UUID requestId = UUID.randomUUID();
        endpoint.open(requestId, "main", ToolRuntimeCatalog.from(
                registry.registrations(), java.util.Set.of()));

        endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                UUID.randomUUID(),
                "main",
                "openallay:resource_read",
                "{\"paths\":[\"/game/runtime\"]}"));
        endpoint.handle(new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                UUID.randomUUID(),
                "main",
                "openallay:resource_read",
                "{\"paths\":[\"/player/profile\"]}"));

        assertEquals(1, captures.get());
        assertFalse(sent.isEmpty());
        ToolInvocationContext requestContext = new ToolInvocationContext(
                requestId.toString(),
                GroundedTestFixtures.fullContext().capturedAt(),
                GroundedTestFixtures.fullContext().caller(),
                GroundedTestFixtures.fullContext().player(),
                GroundedTestFixtures.fullContext().registries(),
                GroundedTestFixtures.fullContext().recipes(),
                GroundedTestFixtures.fullContext().observableGameState(),
                GroundedTestFixtures.fullContext().metrics());
        assertTrue(resources.capture(requestContext, new dev.openallay.model.CancellationSignal())
                .view().require(dev.openallay.resource.vfs.ResourcePath.parse("/game/runtime")) != null);

        assertTrue(endpoint.close(requestId));
        assertThrows(IllegalStateException.class, () -> resources.capture(
                requestContext, new dev.openallay.model.CancellationSignal()));
        resources.close();
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
