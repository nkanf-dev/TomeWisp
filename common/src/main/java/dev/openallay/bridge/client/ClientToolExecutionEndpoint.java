package dev.openallay.bridge.client;

import com.google.gson.Gson;
import dev.openallay.agent.tool.ToolRuntimeCatalog;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.BridgeViewIdentity;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.ClientToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.CallerKind;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolResult;
import dev.openallay.trace.replay.ToolArgumentCodec;
import dev.openallay.trace.replay.ToolResultNormalizer;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Hosts one frozen local capability catalog for each active server-model request.
 * Loader code owns client-thread marshalling in the supplied context provider.
 */
public final class ClientToolExecutionEndpoint {
    @FunctionalInterface
    public interface ContextProvider {
        CompletableFuture<ToolInvocationContext> capture(
                Set<ContextCapability> capabilities, String correlationId);
    }

    @FunctionalInterface
    public interface ResponseSink {
        void send(ClientToolResultChunkPayload chunk);
    }

    private final ContextProvider contexts;
    private final ResponseSink responses;
    private final Gson gson;
    private final ToolArgumentCodec arguments;
    private final ToolResultNormalizer normalizer;
    private final int transportChunkBytes;
    private final Executor worker;
    private final ResourceRequestRegistry resources;
    private final Supplier<ContextBudget> serverBudget;
    private final Map<UUID, RequestState> requests = new ConcurrentHashMap<>();

    public ClientToolExecutionEndpoint(
            ContextProvider contexts,
            ResponseSink responses,
            Gson gson,
            int transportChunkBytes) {
        this(
                contexts,
                responses,
                gson,
                transportChunkBytes,
                null,
                null,
                command -> Thread.ofVirtual()
                        .name("openallay-client-tool-worker")
                        .start(command));
    }

    public ClientToolExecutionEndpoint(
            ContextProvider contexts,
            ResponseSink responses,
            Gson gson,
            int transportChunkBytes,
            ResourceRequestRegistry resources,
            Supplier<ContextBudget> serverBudget) {
        this(
                contexts,
                responses,
                gson,
                transportChunkBytes,
                resources,
                serverBudget,
                command -> Thread.ofVirtual()
                        .name("openallay-client-tool-worker")
                        .start(command));
    }

    ClientToolExecutionEndpoint(
            ContextProvider contexts,
            ResponseSink responses,
            Gson gson,
            int transportChunkBytes,
            Executor worker) {
        this(contexts, responses, gson, transportChunkBytes, null, null, worker);
    }

    ClientToolExecutionEndpoint(
            ContextProvider contexts,
            ResponseSink responses,
            Gson gson,
            int transportChunkBytes,
            ResourceRequestRegistry resources,
            Supplier<ContextBudget> serverBudget,
            Executor worker) {
        if (transportChunkBytes <= 0) {
            throw new IllegalArgumentException("transportChunkBytes must be positive");
        }
        this.contexts = java.util.Objects.requireNonNull(contexts, "contexts");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.gson = java.util.Objects.requireNonNull(gson, "gson");
        this.transportChunkBytes = transportChunkBytes;
        this.worker = java.util.Objects.requireNonNull(worker, "worker");
        this.resources = resources;
        this.serverBudget = serverBudget;
        if ((resources == null) != (serverBudget == null)) {
            throw new IllegalArgumentException(
                    "Resource registry and server context budget must be configured together");
        }
        arguments = new ToolArgumentCodec(gson);
        normalizer = new ToolResultNormalizer(gson);
    }

    public ToolResult<OpenedRequest> open(
            UUID requestId, String sessionId, ToolRuntimeCatalog frozenTools) {
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            return new ToolResult.Failure<>("invalid_session", "Invalid Agent session ID");
        }
        java.util.Objects.requireNonNull(frozenTools, "frozenTools");
        List<String> exported = frozenTools.descriptors().stream()
                .filter(descriptor -> descriptor.access() == ToolAccess.READ_ONLY)
                .map(descriptor -> descriptor.id())
                .sorted()
                .toList();
        ContextBudget budget;
        try {
            budget = resources == null
                    ? null
                    : java.util.Objects.requireNonNull(
                            serverBudget.get(), "Server context budget is unavailable");
        } catch (RuntimeException unavailable) {
            return new ToolResult.Failure<>(
                    "server_model_context_unavailable",
                    "Server model context capability is unavailable");
        }
        Set<ContextCapability> requiredContext = frozenTools.descriptors().stream()
                .filter(descriptor -> exported.contains(descriptor.id()))
                .flatMap(descriptor -> descriptor.requiredContext().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        RequestState state = new RequestState(
                requestId,
                sessionId,
                BridgeViewIdentity.forRequest(
                        requestId, sessionId, BridgeViewIdentity.Owner.CLIENT),
                frozenTools,
                Set.copyOf(exported),
                requiredContext,
                budget);
        if (requests.putIfAbsent(requestId, state) != null) {
            return new ToolResult.Failure<>(
                    "duplicate_request", "Client Tool request ID is already active");
        }
        return new ToolResult.Success<>(new OpenedRequest(requestId, sessionId, exported));
    }

    public ToolResult<VoidResult> handle(ClientToolCallPayload payload) {
        RequestState request = requests.get(payload.requestId());
        if (request == null) {
            sendFailure(payload, "client_tool_unavailable", "Client Tool request is no longer active");
            return new ToolResult.Failure<>(
                    "client_tool_unavailable", "Client Tool request is no longer active");
        }
        if (!request.sessionId.equals(payload.sessionId())) {
            sendFailure(payload, "client_tool_rejected", "Client Tool session does not match");
            return new ToolResult.Failure<>(
                    "client_tool_rejected", "Client Tool session does not match");
        }
        if (!request.viewId.equals(payload.viewId())) {
            sendFailure(payload, "stale_resource", "Client resource view identity does not match");
            return new ToolResult.Failure<>(
                    "stale_resource", "Client resource view identity does not match");
        }
        Tool<?, ?> tool = request.exported.contains(payload.toolId())
                ? request.tools.find(payload.toolId()).orElse(null)
                : null;
        if (tool == null || tool.descriptor().access() != ToolAccess.READ_ONLY) {
            sendFailure(payload, "client_tool_unavailable", "Client Tool is absent or disabled");
            return new ToolResult.Failure<>(
                    "client_tool_unavailable", "Client Tool is absent or disabled");
        }
        CancellationSignal cancellation = new CancellationSignal();
        Registration registration = request.register(payload.invocationId(), cancellation);
        if (registration == Registration.CLOSED) {
            sendFailure(payload, "client_tool_unavailable", "Client Tool request is no longer active");
            return new ToolResult.Failure<>(
                    "client_tool_unavailable", "Client Tool request is no longer active");
        }
        if (registration == Registration.DUPLICATE) {
            return new ToolResult.Failure<>(
                    "duplicate_invocation", "Client Tool invocation ID is already active");
        }
        CompletableFuture<ToolInvocationContext> capture;
        try {
            capture = request.context(contexts, resources);
        } catch (RuntimeException failure) {
            finish(payload, request, tool, new ToolResult.Failure<>(
                    "client_tool_context_failed", "Client Tool context capture failed"));
            return new ToolResult.Success<>(new VoidResult());
        }
        if (capture == null) {
            finish(payload, request, tool, new ToolResult.Failure<>(
                    "client_tool_context_failed", "Client Tool context capture failed"));
            return new ToolResult.Success<>(new VoidResult());
        }
        capture.thenApplyAsync(
                        context -> invoke(tool, context, payload.argumentsJson(), cancellation),
                        worker)
                .exceptionally(ignored -> new ToolResult.Failure<>(
                        "client_tool_context_failed", "Client Tool context capture failed"))
                .thenAccept(result -> finish(payload, request, tool, result));
        return new ToolResult.Success<>(new VoidResult());
    }

    public boolean cancel(ClientToolCancelPayload payload) {
        RequestState request = requests.get(payload.requestId());
        if (request == null) {
            return false;
        }
        CancellationSignal cancellation = request.remove(payload.invocationId());
        return cancellation != null && cancellation.cancel();
    }

    public boolean close(UUID requestId) {
        RequestState request = requests.remove(requestId);
        if (request == null) {
            return false;
        }
        request.close();
        return true;
    }

    public int disconnect() {
        List<UUID> active = List.copyOf(requests.keySet());
        active.forEach(this::close);
        return active.size();
    }

    public int activeRequests() {
        return requests.size();
    }

    private ToolResult<?> invoke(
            Tool<?, ?> tool,
            ToolInvocationContext context,
            String argumentsJson,
            CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(argumentsJson);
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "Client Tool arguments are not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "Client Tool arguments must be an object");
        }
        ToolResult<?> decoded = arguments.decode(
                parsed.getAsJsonObject(), tool.descriptor().inputType());
        if (decoded instanceof ToolResult.Failure<?> failure) {
            return failure;
        }
        cancellation.throwIfCancelled();
        try {
            return invokeTyped(tool, context, ((ToolResult.Success<?>) decoded).value());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("tool_failure", "Client Tool execution failed");
        }
    }

    private void finish(
            ClientToolCallPayload payload,
            RequestState request,
            Tool<?, ?> tool,
            ToolResult<?> result) {
        CancellationSignal cancellation = request.remove(payload.invocationId());
        if (cancellation == null
                || cancellation.isCancelled()
                || requests.get(payload.requestId()) != request) {
            return;
        }
        try {
            sendNormalized(
                    payload.requestId(), payload.invocationId(), payload.viewId(),
                    normalizer.normalize(result, tool.descriptor().outputType()));
        } catch (RuntimeException failure) {
            sendFailure(payload, "client_tool_result_invalid", "Client Tool result was invalid");
        }
    }

    private void sendFailure(ClientToolCallPayload payload, String code, String message) {
        sendNormalized(
                payload.requestId(),
                payload.invocationId(),
                payload.viewId(),
                normalizer.normalize(new ToolResult.Failure<>(code, message), Object.class));
    }

    private void sendNormalized(
            UUID requestId,
            UUID invocationId,
            String viewId,
            com.google.gson.JsonObject normalized) {
        new ResultChunker().split(
                        invocationId, viewId, gson.toJson(normalized), transportChunkBytes)
                .stream()
                .map(chunk -> ClientToolResultChunkPayload.from(requestId, chunk))
                .forEach(responses::send);
    }

    @SuppressWarnings("unchecked")
    private static <I, O> ToolResult<O> invokeTyped(
            Tool<?, ?> raw, ToolInvocationContext context, Object input) {
        return ((Tool<I, O>) raw).invoke(context, (I) input);
    }

    public record OpenedRequest(UUID requestId, String sessionId, List<String> clientToolIds) {
        public OpenedRequest {
            java.util.Objects.requireNonNull(requestId, "requestId");
            clientToolIds = List.copyOf(clientToolIds);
        }
    }

    public record VoidResult() {}

    private enum Registration {
        REGISTERED,
        DUPLICATE,
        CLOSED
    }

    private static final class RequestState {
        private final UUID requestId;
        private final String sessionId;
        private final String viewId;
        private final ToolRuntimeCatalog tools;
        private final Set<String> exported;
        private final Set<ContextCapability> requiredContext;
        private final ContextBudget contextBudget;
        private final Map<UUID, CancellationSignal> pending = new HashMap<>();
        private CompletableFuture<ToolInvocationContext> frozenContext;
        private ResourceRequestRegistry.RequestHandle resourceHandle;
        private boolean closed;

        private RequestState(
                UUID requestId,
                String sessionId,
                String viewId,
                ToolRuntimeCatalog tools,
                Set<String> exported,
                Set<ContextCapability> requiredContext,
                ContextBudget contextBudget) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.viewId = viewId;
            this.tools = tools;
            this.exported = exported;
            this.requiredContext = requiredContext;
            this.contextBudget = contextBudget;
        }

        private synchronized CompletableFuture<ToolInvocationContext> context(
                ContextProvider contexts, ResourceRequestRegistry resources) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Client Tool request is no longer active"));
            }
            if (frozenContext != null) {
                return frozenContext;
            }
            CompletableFuture<ToolInvocationContext> captured =
                    contexts.capture(requiredContext, requestId.toString());
            if (captured == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Client Tool context capture failed"));
            }
            frozenContext = captured.thenApply(context -> bind(context, resources));
            return frozenContext;
        }

        private ToolInvocationContext bind(
                ToolInvocationContext context, ResourceRequestRegistry resources) {
            java.util.Objects.requireNonNull(context, "context");
            ResourceRequestRegistry.RequestHandle opened = null;
            if (resources != null) {
                if (context.caller().kind() != CallerKind.PLAYER) {
                    throw new IllegalStateException(
                            "Server-hosted client Tools require a captured player caller");
                }
                UUID actorId = context.caller().uuid();
                opened = resources.open(
                        actorId,
                        sessionId,
                        requestId,
                        resources.connectionGeneration(actorId),
                        "client_for_server_model",
                        exported,
                        contextBudget,
                        context);
            }
            synchronized (this) {
                if (closed) {
                    if (opened != null) opened.close();
                    throw new IllegalStateException("Client Tool request is no longer active");
                }
                resourceHandle = opened;
                return context;
            }
        }

        private synchronized Registration register(
                UUID invocationId, CancellationSignal cancellation) {
            if (closed) {
                return Registration.CLOSED;
            }
            if (pending.putIfAbsent(invocationId, cancellation) != null) {
                return Registration.DUPLICATE;
            }
            return Registration.REGISTERED;
        }

        private synchronized CancellationSignal remove(UUID invocationId) {
            return pending.remove(invocationId);
        }

        private void close() {
            List<CancellationSignal> cancellations;
            ResourceRequestRegistry.RequestHandle handle;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                cancellations = List.copyOf(pending.values());
                pending.clear();
                handle = resourceHandle;
                resourceHandle = null;
            }
            cancellations.forEach(CancellationSignal::cancel);
            if (handle != null) handle.close();
        }
    }
}
