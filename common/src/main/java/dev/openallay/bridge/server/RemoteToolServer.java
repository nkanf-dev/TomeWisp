package dev.openallay.bridge.server;

import com.google.gson.Gson;
import dev.openallay.bridge.CorrelationRegistry;
import dev.openallay.bridge.protocol.RemoteCancelPayload;
import dev.openallay.bridge.protocol.RemoteToolCallPayload;
import dev.openallay.bridge.protocol.RemoteToolResultChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolResult;
import dev.openallay.trace.replay.ToolArgumentCodec;
import dev.openallay.trace.replay.ToolResultNormalizer;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RemoteToolServer {
    @FunctionalInterface
    public interface ContextProvider {
        CompletableFuture<ToolInvocationContext> capture(
                UUID actorId, Set<ContextCapability> capabilities, String correlationId);
    }

    @FunctionalInterface
    public interface ResponseSink {
        void send(UUID actorId, RemoteToolResultChunkPayload chunk);
    }

    private final ExportedToolPolicy policy;
    private final ContextProvider contexts;
    private final ResponseSink responses;
    private final CorrelationRegistry correlations;
    private final ToolArgumentCodec arguments;
    private final ToolResultNormalizer normalizer;
    private final Gson gson;
    private final int transportChunkBytes;

    public RemoteToolServer(
            ExportedToolPolicy policy,
            ContextProvider contexts,
            ResponseSink responses,
            CorrelationRegistry correlations,
            Gson gson,
            int transportChunkBytes) {
        if (transportChunkBytes <= 0) {
            throw new IllegalArgumentException("transportChunkBytes must be positive");
        }
        this.policy = policy;
        this.contexts = contexts;
        this.responses = responses;
        this.correlations = correlations;
        this.gson = gson;
        this.transportChunkBytes = transportChunkBytes;
        arguments = new ToolArgumentCodec(gson);
        normalizer = new ToolResultNormalizer(gson);
    }

    public ToolResult<VoidResult> handle(UUID sender, RemoteToolCallPayload payload) {
        Tool<?, ?> tool = policy.find(payload.toolId()).orElse(null);
        if (tool == null) {
            return new ToolResult.Failure<>("remote_tool_denied", "Tool is not exported as read-only");
        }
        CancellationSignal cancellation = new CancellationSignal();
        if (!correlations.register(sender, payload.correlationId(), cancellation)) {
            return new ToolResult.Failure<>("duplicate_correlation", "Correlation ID is already active");
        }
        contexts.capture(
                        sender,
                        tool.descriptor().requiredContext(),
                        payload.correlationId().toString())
                .thenApply(context -> invoke(tool, context, payload.argumentsJson(), cancellation))
                .exceptionally(throwable -> new ToolResult.Failure<>(
                        "remote_tool_failure", safeMessage(throwable)))
                .thenAccept(result -> finish(
                        sender, payload.correlationId(), payload.viewId(), tool, result));
        return new ToolResult.Success<>(new VoidResult());
    }

    public boolean cancel(UUID sender, RemoteCancelPayload payload) {
        return correlations.cancel(sender, payload.correlationId());
    }

    public int disconnect(UUID sender) {
        return correlations.cancelActor(sender);
    }

    private void finish(
            UUID actor,
            UUID correlation,
            String viewId,
            Tool<?, ?> tool,
            ToolResult<?> result) {
        if (!correlations.complete(actor, correlation)) {
            return;
        }
        String json = gson.toJson(normalizer.normalize(result, tool.descriptor().outputType()));
        new ResultChunker().split(correlation, viewId, json, transportChunkBytes)
                .forEach(chunk -> responses.send(actor, chunk));
    }

    private ToolResult<?> invoke(
            Tool<?, ?> tool,
            ToolInvocationContext context,
            String argumentsJson,
            CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(argumentsJson);
        if (!parsed.isJsonObject()) {
            return new ToolResult.Failure<>("invalid_arguments", "Remote tool arguments must be an object");
        }
        ToolResult<?> decoded = arguments.decode(parsed.getAsJsonObject(), tool.descriptor().inputType());
        if (decoded instanceof ToolResult.Failure<?> failure) {
            return failure;
        }
        cancellation.throwIfCancelled();
        return invokeTyped(tool, context, ((ToolResult.Success<?>) decoded).value());
    }

    @SuppressWarnings("unchecked")
    private static <I, O> ToolResult<O> invokeTyped(
            Tool<?, ?> raw, ToolInvocationContext context, Object input) {
        return ((Tool<I, O>) raw).invoke(context, (I) input);
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record VoidResult() {}
}
