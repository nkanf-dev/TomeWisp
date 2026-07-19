package dev.tomewisp.bridge.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.agent.tool.ToolRuntimeCatalog;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.ClientToolCallPayload;
import dev.tomewisp.bridge.protocol.ClientToolCancelPayload;
import dev.tomewisp.bridge.protocol.ClientToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ResultChunker;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.replay.ToolResultNormalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * Owns server-side correlations for client-resident Tools used by a server-hosted Agent.
 * Each opened executor freezes one actor/request capability intersection.
 */
public final class PlayerClientToolRouter {
    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tomewisp-client-tool-timeouts");
                thread.setDaemon(true);
                return thread;
            });

    public interface Transport {
        boolean call(UUID actorId, ClientToolCallPayload payload);

        void cancel(UUID actorId, ClientToolCancelPayload payload);
    }

    private final ToolRuntimeCatalog trustedTools;
    private final Gson gson;
    private final Transport transport;
    private final ToolResultNormalizer normalizer;
    private final Duration resultTimeout;
    private final Map<RequestKey, RequestExecutor> active = new ConcurrentHashMap<>();

    public PlayerClientToolRouter(ToolRegistry tools, Gson gson, Transport transport) {
        this(tools, gson, transport, Duration.ofMinutes(5));
    }

    public PlayerClientToolRouter(
            ToolRegistry tools, Gson gson, Transport transport, Duration resultTimeout) {
        java.util.Objects.requireNonNull(tools, "tools");
        Set<String> nonReadOnly = tools.descriptors().stream()
                .filter(descriptor -> descriptor.access() != ToolAccess.READ_ONLY)
                .map(descriptor -> descriptor.id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        trustedTools = ToolRuntimeCatalog.from(tools.registrations(), nonReadOnly);
        this.gson = java.util.Objects.requireNonNull(gson, "gson");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.resultTimeout = java.util.Objects.requireNonNull(resultTimeout, "resultTimeout");
        if (resultTimeout.isZero() || resultTimeout.isNegative()) {
            throw new IllegalArgumentException("resultTimeout must be positive");
        }
        normalizer = new ToolResultNormalizer(gson);
    }

    public ToolResult<AgentToolExecutor> open(
            UUID actorId,
            UUID requestId,
            String sessionId,
            List<String> advertisedClientToolIds) {
        java.util.Objects.requireNonNull(actorId, "actorId");
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            return new ToolResult.Failure<>("invalid_session", "Invalid Agent session ID");
        }
        Set<String> accepted = List.copyOf(advertisedClientToolIds).stream()
                .filter(toolId -> trustedTools.find(toolId).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        RequestKey key = new RequestKey(actorId, requestId);
        RequestExecutor executor = new RequestExecutor(key, sessionId, accepted);
        if (active.putIfAbsent(key, executor) != null) {
            return new ToolResult.Failure<>(
                    "duplicate_request", "Client Tool request ID is already active");
        }
        return new ToolResult.Success<>(executor);
    }

    public boolean receive(UUID actorId, ClientToolResultChunkPayload chunk) {
        RequestExecutor executor = active.get(new RequestKey(actorId, chunk.requestId()));
        return executor != null && executor.receive(chunk);
    }

    /** Converts a correlated transport failure into Tool results for every pending invocation. */
    public int fail(UUID actorId, UUID requestId, String code, String message) {
        RequestExecutor executor = active.get(new RequestKey(actorId, requestId));
        return executor == null ? 0 : executor.failPending(code, message);
    }

    public boolean close(UUID actorId, UUID requestId) {
        RequestExecutor executor = active.remove(new RequestKey(actorId, requestId));
        if (executor == null) {
            return false;
        }
        executor.cancelPending();
        return true;
    }

    public int disconnect(UUID actorId) {
        List<RequestKey> owned = active.keySet().stream()
                .filter(key -> key.actorId.equals(actorId))
                .toList();
        owned.forEach(key -> close(key.actorId, key.requestId));
        return owned.size();
    }

    public int activeRequests() {
        return active.size();
    }

    public int activeResultAssemblies(UUID actorId, UUID requestId) {
        RequestExecutor executor = active.get(new RequestKey(actorId, requestId));
        return executor == null ? 0 : executor.reassembler.activeAssemblies();
    }

    private final class RequestExecutor implements AgentToolExecutor {
        private final RequestKey key;
        private final String sessionId;
        private final Set<String> clientTools;
        private final LocalAgentToolExecutor local = new LocalAgentToolExecutor(trustedTools, gson);
        private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
        private final ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();

        private RequestExecutor(RequestKey key, String sessionId, Set<String> clientTools) {
            this.key = key;
            this.sessionId = sessionId;
            this.clientTools = Set.copyOf(clientTools);
        }

        @Override
        public List<ModelToolDefinition> definitions() {
            return local.definitions();
        }

        @Override
        public Set<ContextCapability> requiredContext() {
            // Server placement remains available for server-authoritative sections, so the
            // server snapshot is detached once before model work just as it was previously.
            return local.requiredContext();
        }

        @Override
        public Optional<String> canonicalToolId(String modelToolName) {
            return local.canonicalToolId(modelToolName);
        }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            String toolId = canonicalToolId(modelToolName).orElse(UNKNOWN_TOOL_ID);
            if (toolId.equals(UNKNOWN_TOOL_ID)) {
                return completedFailure(
                        toolId, "tool_unavailable", "Tool is unavailable in this request");
            }
            if (!useClient(toolId, arguments)) {
                return local.execute(modelToolName, arguments, context, cancellation);
            }
            UUID invocationId = UUID.randomUUID();
            CompletableFuture<AgentToolResult> result = new CompletableFuture<>();
            Pending value = new Pending(toolId, result);
            pending.put(invocationId, value);
            cancellation.onCancel(() -> cancelInvocation(invocationId, value));
            ClientToolCallPayload payload = new ClientToolCallPayload(
                    BridgeProtocol.VERSION,
                    key.requestId,
                    invocationId,
                    sessionId,
                    toolId,
                    arguments.toString());
            synchronized (value) {
                if (pending.get(invocationId) != value || cancellation.isCancelled()) {
                    return result;
                }
                boolean sent;
                try {
                    sent = transport.call(key.actorId, payload);
                } catch (RuntimeException failure) {
                    sent = false;
                }
                value.dispatched = sent;
                if (!sent && pending.remove(invocationId, value)) {
                    result.complete(failure(
                            toolId,
                            "client_tool_bridge_unavailable",
                            "Player client Tool connection is unavailable"));
                } else if (sent && pending.get(invocationId) == value) {
                    ScheduledFuture<?> deadline = TIMEOUTS.schedule(
                            () -> timeoutInvocation(invocationId, value),
                            resultTimeout.toMillis(),
                            TimeUnit.MILLISECONDS);
                    value.setDeadline(deadline);
                }
            }
            return result;
        }

        private boolean useClient(String toolId, JsonObject arguments) {
            if (!clientTools.contains(toolId)) {
                return false;
            }
            if (!toolId.equals("tomewisp:inspect_game_state")) {
                return true;
            }
            String section = arguments != null
                            && arguments.has("section")
                            && arguments.get("section").isJsonPrimitive()
                    ? arguments.get("section").getAsString()
                    : "";
            return !section.equalsIgnoreCase("WORLD_QUERY");
        }

        private boolean receive(ClientToolResultChunkPayload chunk) {
            Pending value = pending.get(chunk.invocationId());
            if (value == null) {
                return false;
            }
            synchronized (value) {
                if (pending.get(chunk.invocationId()) != value) {
                    return false;
                }
                try {
                    Optional<String> complete = reassembler.accept(chunk.asRemoteChunk());
                    if (complete.isEmpty()) {
                        return true;
                    }
                    if (!pending.remove(chunk.invocationId(), value)) {
                        reassembler.cancel(chunk.invocationId());
                        return false;
                    }
                    value.cancelDeadline();
                    JsonObject normalized = JsonParser.parseString(
                                    complete.orElseThrow())
                            .getAsJsonObject();
                    JsonObject validated = validateNormalized(value.toolId, normalized);
                    if (validated == null) {
                        value.result.complete(failure(
                                value.toolId,
                                "client_tool_result_invalid",
                                "Player client Tool result was invalid"));
                        return false;
                    }
                    boolean failed = validated.get("status").getAsString().equals("failure");
                    value.result.complete(new AgentToolResult(value.toolId, validated, failed));
                    return true;
                } catch (RuntimeException failure) {
                    pending.remove(chunk.invocationId(), value);
                    value.cancelDeadline();
                    reassembler.cancel(chunk.invocationId());
                    value.result.complete(failure(
                            value.toolId,
                            "client_tool_result_invalid",
                            "Player client Tool result was invalid"));
                    return false;
                }
            }
        }

        private int failPending(String code, String message) {
            List<Map.Entry<UUID, Pending>> values = List.copyOf(pending.entrySet());
            values.forEach(entry -> {
                Pending value = entry.getValue();
                synchronized (value) {
                    if (!pending.remove(entry.getKey(), value)) {
                        return;
                    }
                    value.cancelDeadline();
                    reassembler.cancel(entry.getKey());
                    value.result.complete(failure(value.toolId, code, message));
                }
            });
            return values.size();
        }

        private void cancelPending() {
            List<Map.Entry<UUID, Pending>> values = List.copyOf(pending.entrySet());
            values.forEach(entry -> cancelInvocation(entry.getKey(), entry.getValue()));
        }

        private void cancelInvocation(UUID invocationId, Pending value) {
            boolean dispatched;
            synchronized (value) {
                if (!pending.remove(invocationId, value)) {
                    return;
                }
                dispatched = value.dispatched;
            }
            value.cancelDeadline();
            reassembler.cancel(invocationId);
            if (dispatched) {
                try {
                    transport.cancel(key.actorId, new ClientToolCancelPayload(
                            BridgeProtocol.VERSION, key.requestId, invocationId));
                } catch (RuntimeException ignored) {
                    // The enclosing cancellation still owns the terminal request state.
                }
            }
            value.result.completeExceptionally(new ModelClientException(new ModelFailure(
                    "agent_cancelled", "Client Tool invocation was cancelled", null)));
        }

        private void timeoutInvocation(UUID invocationId, Pending value) {
            synchronized (value) {
                if (!pending.remove(invocationId, value)) {
                    return;
                }
                reassembler.cancel(invocationId);
            }
            try {
                transport.cancel(key.actorId, new ClientToolCancelPayload(
                        BridgeProtocol.VERSION, key.requestId, invocationId));
            } catch (RuntimeException ignored) {
                // Timeout remains a complete Tool result even if cancellation cannot be sent.
            }
            value.result.complete(failure(
                    value.toolId,
                    "client_tool_timeout",
                    "Player client Tool result timed out"));
        }
    }

    private CompletableFuture<AgentToolResult> completedFailure(
            String toolId, String code, String message) {
        return CompletableFuture.completedFuture(failure(toolId, code, message));
    }

    private AgentToolResult failure(String toolId, String code, String message) {
        return new AgentToolResult(
                toolId,
                normalizer.normalize(new ToolResult.Failure<>(code, message), Object.class),
                true);
    }

    private JsonObject validateNormalized(String toolId, JsonObject normalized) {
        if (normalized == null
                || !normalized.has("status")
                || !normalized.get("status").isJsonPrimitive()) {
            return null;
        }
        String status = normalized.get("status").getAsString();
        if (status.equals("failure")) {
            if (!normalized.keySet().equals(Set.of("status", "code", "message"))) {
                return null;
            }
            try {
                return normalizer.normalize(
                        new ToolResult.Failure<>(
                                normalized.get("code").getAsString(),
                                normalized.get("message").getAsString()),
                        Object.class);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        if (!status.equals("success")
                || !normalized.keySet().equals(Set.of("status", "outputType", "value"))) {
            return null;
        }
        dev.tomewisp.tool.Tool<?, ?> tool = trustedTools.find(toolId).orElse(null);
        if (tool == null
                || !normalized.get("outputType").isJsonPrimitive()
                || !normalized.get("outputType").getAsString()
                        .equals(tool.descriptor().outputType().getName())) {
            return null;
        }
        try {
            Object value = gson.fromJson(normalized.get("value"), tool.descriptor().outputType());
            return normalizer.normalize(
                    new ToolResult.Success<>(value), tool.descriptor().outputType());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private record RequestKey(UUID actorId, UUID requestId) {}

    private static final class Pending {
        private final String toolId;
        private final CompletableFuture<AgentToolResult> result;
        private volatile ScheduledFuture<?> deadline;
        private boolean dispatched;

        private Pending(String toolId, CompletableFuture<AgentToolResult> result) {
            this.toolId = toolId;
            this.result = result;
        }

        private void setDeadline(ScheduledFuture<?> replacement) {
            deadline = replacement;
            if (result.isDone()) {
                replacement.cancel(false);
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
