package dev.tomewisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.RemoteCancelPayload;
import dev.tomewisp.bridge.protocol.RemoteToolCallPayload;
import dev.tomewisp.bridge.protocol.RemoteToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ResultChunker;
import dev.tomewisp.bridge.server.ExportedToolPolicy;
import dev.tomewisp.bridge.server.RemoteToolServer;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class RemoteToolBridgeTest {
    @Test
    void derivesIdentityFromSenderAndSuppressesCancelledLateResult() {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test", List.of(new FactTool()));
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        CompletableFuture<ToolInvocationContext> context = new CompletableFuture<>();
        List<RemoteToolResultChunkPayload> sent = new ArrayList<>();
        RemoteToolServer server = new RemoteToolServer(
                new ExportedToolPolicy(tools, Set.of("test:fact")),
                (actor, capabilities, id) -> context,
                (actor, chunk) -> sent.add(chunk),
                new CorrelationRegistry(),
                new Gson(),
                5);
        RemoteToolCallPayload call = new RemoteToolCallPayload(
                BridgeProtocol.VERSION, correlation, "main", "test:fact", "{\"value\":7}");

        assertInstanceOf(ToolResult.Success.class, server.handle(owner, call));
        assertFalse(server.cancel(attacker, new RemoteCancelPayload(BridgeProtocol.VERSION, correlation)));
        assertTrue(server.cancel(owner, new RemoteCancelPayload(BridgeProtocol.VERSION, correlation)));
        context.complete(ToolInvocationContext.developmentConsole(correlation.toString()));
        assertTrue(sent.isEmpty());
    }

    @Test
    void sendsCompleteNormalizedResultOnlyToOwningConnection() {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test", List.of(new FactTool()));
        UUID owner = UUID.randomUUID();
        List<UUID> recipients = new ArrayList<>();
        List<RemoteToolResultChunkPayload> sent = new ArrayList<>();
        RemoteToolServer server = new RemoteToolServer(
                new ExportedToolPolicy(tools, Set.of("test:fact")),
                (actor, capabilities, id) -> CompletableFuture.completedFuture(
                        ToolInvocationContext.developmentConsole(id)),
                (actor, chunk) -> { recipients.add(actor); sent.add(chunk); },
                new CorrelationRegistry(), new Gson(), 3);
        UUID correlation = UUID.randomUUID();
        server.handle(owner, new RemoteToolCallPayload(
                BridgeProtocol.VERSION, correlation, "main", "test:fact", "{\"value\":42}"));

        assertFalse(sent.isEmpty());
        assertTrue(recipients.stream().allMatch(owner::equals));
        ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
        String result = null;
        for (RemoteToolResultChunkPayload chunk : sent) {
            var complete = reassembler.accept(chunk);
            if (complete.isPresent()) result = complete.orElseThrow();
        }
        assertTrue(result.contains("42"));
    }

    private static final class FactTool implements Tool<FactTool.Input, FactTool.Output> {
        record Input(int value) {}
        record Output(int value) {}
        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "test:fact", "Return fact", Input.class, Output.class, ToolAccess.READ_ONLY);
        @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }
        @Override public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output(input.value()));
        }
    }
}
