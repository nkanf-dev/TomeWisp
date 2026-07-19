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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RemoteToolExecutor implements AgentToolExecutor {
    private static final String MODEL_PREFIX = "server__";
    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tomewisp-server-tool-timeouts");
                thread.setDaemon(true);
                return thread;
            });

    public interface Transport {
        void call(RemoteToolCallPayload payload);
        void cancel(RemoteCancelPayload payload);
    }

    private final RemoteCapabilityStore capabilities;
    private final Transport transport;
    private final Duration resultTimeout;
    private final ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public RemoteToolExecutor(RemoteCapabilityStore capabilities, Transport transport) {
        this(capabilities, transport, BridgeProtocol.PARTIAL_ASSEMBLY_TIMEOUT);
    }

    RemoteToolExecutor(
            RemoteCapabilityStore capabilities, Transport transport, Duration resultTimeout) {
        this.capabilities = capabilities;
        this.transport = transport;
        this.resultTimeout = java.util.Objects.requireNonNull(resultTimeout, "resultTimeout");
        if (resultTimeout.isZero() || resultTimeout.isNegative()) {
            throw new IllegalArgumentException("resultTimeout must be positive");
        }
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        return capabilities.snapshot().remoteTools().stream()
                .map(tool -> new ModelToolDefinition(
                        MODEL_PREFIX + codec().encode(tool.id()),
                        "[server read tool] " + tool.description(),
                        JsonParser.parseString(tool.inputSchemaJson()).getAsJsonObject()))
                .toList();
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        return Set.of();
    }

    @Override
    public Optional<String> canonicalToolId(String modelToolName) {
        if (modelToolName == null || !modelToolName.startsWith(MODEL_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec().decode(modelToolName.substring(MODEL_PREFIX.length())));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        if (!modelToolName.startsWith(MODEL_PREFIX)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Not a server tool name: " + modelToolName));
        }
        String toolId = codec().decode(modelToolName.substring(MODEL_PREFIX.length()));
        UUID correlation = UUID.randomUUID();
        Pending value = new Pending(toolId, new CompletableFuture<>());
        pending.put(correlation, value);
        cancellation.onCancel(() -> cancelInvocation(correlation, value));
        RemoteToolCallPayload payload = new RemoteToolCallPayload(
                BridgeProtocol.VERSION,
                correlation,
                context.correlationId(),
                toolId,
                arguments.toString());
        synchronized (value) {
            if (pending.get(correlation) != value || cancellation.isCancelled()) {
                return value.result;
            }
            try {
                transport.call(payload);
                value.dispatched = true;
            } catch (RuntimeException unavailable) {
                if (pending.remove(correlation, value)) {
                    value.result.complete(failure(
                            toolId,
                            "server_tool_bridge_unavailable",
                            "Server Tool connection is unavailable"));
                }
                return value.result;
            }
            if (pending.get(correlation) == value) {
                ScheduledFuture<?> deadline = TIMEOUTS.schedule(
                        () -> timeoutInvocation(correlation, value),
                        resultTimeout.toMillis(),
                        TimeUnit.MILLISECONDS);
                value.setDeadline(deadline);
            }
        }
        return value.result;
    }

    public boolean receive(RemoteToolResultChunkPayload chunk) {
        Pending value = pending.get(chunk.correlationId());
        if (value == null) {
            return false;
        }
        synchronized (value) {
            if (pending.get(chunk.correlationId()) != value) {
                return false;
            }
            try {
                var complete = reassembler.accept(chunk);
                if (complete.isPresent()) {
                    if (!pending.remove(chunk.correlationId(), value)) {
                        reassembler.cancel(chunk.correlationId());
                        return false;
                    }
                    value.cancelDeadline();
                    JsonObject normalized =
                            JsonParser.parseString(complete.orElseThrow()).getAsJsonObject();
                    if (!normalized.has("status") || !normalized.get("status").isJsonPrimitive()) {
                        throw new IllegalArgumentException("Remote Tool result has no status");
                    }
                    boolean failure = normalized.has("status")
                            && normalized.get("status").getAsString().equals("failure");
                    value.result.complete(new AgentToolResult(value.toolId, normalized, failure));
                }
                return true;
            } catch (RuntimeException failure) {
                pending.remove(chunk.correlationId(), value);
                value.cancelDeadline();
                reassembler.cancel(chunk.correlationId());
                value.result.complete(failure(
                        value.toolId,
                        "server_tool_result_invalid",
                        "Server Tool result was invalid"));
                return false;
            }
        }
    }

    public void disconnect() {
        capabilities.clear();
        List<Map.Entry<UUID, Pending>> values = List.copyOf(pending.entrySet());
        values.forEach(entry -> {
            Pending value = entry.getValue();
            synchronized (value) {
                if (!pending.remove(entry.getKey(), value)) {
                    return;
                }
                value.cancelDeadline();
                reassembler.cancel(entry.getKey());
                value.result.completeExceptionally(new ModelClientException(
                        new ModelFailure(
                                "server_disconnected",
                                "Server enhancement connection closed",
                                null)));
            }
        });
        reassembler.clear();
    }

    private ToolNameCodec codec() {
        return new ToolNameCodec(capabilities.snapshot().remoteTools().stream()
                .map(value -> value.id()).toList());
    }

    private void cancelInvocation(UUID correlation, Pending value) {
        boolean dispatched;
        synchronized (value) {
            if (!pending.remove(correlation, value)) {
                return;
            }
            dispatched = value.dispatched;
        }
        value.cancelDeadline();
        reassembler.cancel(correlation);
        if (dispatched) {
            try {
                transport.cancel(new RemoteCancelPayload(BridgeProtocol.VERSION, correlation));
            } catch (RuntimeException ignored) {
                // The enclosing cancellation still owns the terminal request state.
            }
        }
        value.result.completeExceptionally(new ModelClientException(
                new ModelFailure("agent_cancelled", "Remote Tool call was cancelled", null)));
    }

    private void timeoutInvocation(UUID correlation, Pending value) {
        synchronized (value) {
            if (!pending.remove(correlation, value)) {
                return;
            }
            reassembler.cancel(correlation);
        }
        try {
            transport.cancel(new RemoteCancelPayload(BridgeProtocol.VERSION, correlation));
        } catch (RuntimeException ignored) {
            // Timeout remains a complete Tool result even when cancellation cannot be sent.
        }
        value.result.complete(failure(
                value.toolId, "server_tool_timeout", "Server Tool result timed out"));
    }

    private static AgentToolResult failure(String toolId, String code, String message) {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "failure");
        normalized.addProperty("code", code);
        normalized.addProperty("message", message);
        return new AgentToolResult(toolId, normalized, true);
    }

    int activeResultAssemblies() {
        return reassembler.activeAssemblies();
    }

    private static final class Pending {
        private final String toolId;
        private final CompletableFuture<AgentToolResult> result;
        private volatile ScheduledFuture<?> deadline;
        private boolean dispatched;

        private Pending(String toolId, CompletableFuture<AgentToolResult> result) {
            this.toolId = toolId;
            this.result = result;
        }

        private void setDeadline(ScheduledFuture<?> deadline) {
            this.deadline = deadline;
            if (result.isDone()) {
                deadline.cancel(false);
            }
        }

        private void cancelDeadline() {
            ScheduledFuture<?> current = deadline;
            if (current != null) {
                current.cancel(false);
            }
        }
    }
}
