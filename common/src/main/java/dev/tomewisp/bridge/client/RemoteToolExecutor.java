package dev.tomewisp.bridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.agent.tool.ToolNameCodec;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.RemoteCancelPayload;
import dev.tomewisp.bridge.protocol.RemoteToolCallPayload;
import dev.tomewisp.bridge.protocol.RemoteToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ResultChunker;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteToolExecutor implements AgentToolExecutor {
    public interface Transport {
        void call(RemoteToolCallPayload payload);
        void cancel(RemoteCancelPayload payload);
    }

    private final RemoteCapabilityStore capabilities;
    private final Transport transport;
    private final ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public RemoteToolExecutor(RemoteCapabilityStore capabilities, Transport transport) {
        this.capabilities = capabilities;
        this.transport = transport;
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        return capabilities.snapshot().remoteTools().stream()
                .map(tool -> new ModelToolDefinition(
                        codec().encode(tool.id()),
                        tool.description(),
                        JsonParser.parseString(tool.inputSchemaJson()).getAsJsonObject()))
                .toList();
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        return Set.of();
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        String toolId = codec().decode(modelToolName);
        UUID correlation = UUID.randomUUID();
        Pending value = new Pending(toolId, new CompletableFuture<>());
        pending.put(correlation, value);
        cancellation.onCancel(() -> {
            Pending removed = pending.remove(correlation);
            reassembler.cancel(correlation);
            transport.cancel(new RemoteCancelPayload(BridgeProtocol.VERSION, correlation));
            if (removed != null) {
                removed.result.completeExceptionally(new ModelClientException(
                        new ModelFailure("agent_cancelled", "Remote tool call was cancelled", null)));
            }
        });
        transport.call(new RemoteToolCallPayload(
                BridgeProtocol.VERSION,
                correlation,
                context.correlationId(),
                toolId,
                arguments.toString()));
        return value.result;
    }

    public boolean receive(RemoteToolResultChunkPayload chunk) {
        Pending value = pending.get(chunk.correlationId());
        if (value == null) {
            return false;
        }
        try {
            var complete = reassembler.accept(chunk);
            if (complete.isPresent()) {
                pending.remove(chunk.correlationId());
                JsonObject normalized = JsonParser.parseString(complete.orElseThrow()).getAsJsonObject();
                boolean failure = normalized.has("status")
                        && normalized.get("status").getAsString().equals("failure");
                value.result.complete(new AgentToolResult(value.toolId, normalized, failure));
            }
            return true;
        } catch (RuntimeException failure) {
            pending.remove(chunk.correlationId());
            value.result.completeExceptionally(failure);
            return false;
        }
    }

    private ToolNameCodec codec() {
        return new ToolNameCodec(capabilities.snapshot().remoteTools().stream()
                .map(value -> value.id()).toList());
    }

    private record Pending(String toolId, CompletableFuture<AgentToolResult> result) {}
}
