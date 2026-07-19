package dev.tomewisp.server;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentRequest;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.bridge.protocol.ServerAgentEventCodec;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentHistoryMessage;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class ServerAgentService {
    @FunctionalInterface
    public interface ContextProvider {
        CompletableFuture<ToolInvocationContext> capture(
                UUID actorId, Set<ContextCapability> capabilities, String correlationId);
    }

    @FunctionalInterface
    public interface RequestRuntimeFactory {
        ToolResult<RequestRuntime> create(UUID actorId, ServerAgentRequestPayload payload);
    }

    private final RequestRuntimeFactory runtimes;
    private final AgentSessionStore sessions;
    private final ContextProvider contexts;
    private final ServerGuideEvents events;
    private final ServerAgentEventCodec eventCodec;
    private final String systemPrompt;
    private final Function<dev.tomewisp.model.CancellationSignal, CompletableFuture<Void>> dispatchReady;
    private final Map<UUID, Owner> active = new ConcurrentHashMap<>();

    public ServerAgentService(
            GameGuideAgent agent,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            ContextProvider contexts,
            ServerGuideEvents events,
            Gson gson,
            String systemPrompt) {
        this(agent, tools, sessions, contexts, events, gson, systemPrompt,
                cancellation -> CompletableFuture.completedFuture(null));
    }

    public ServerAgentService(
            GameGuideAgent agent,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            ContextProvider contexts,
            ServerGuideEvents events,
            Gson gson,
            String systemPrompt,
            Function<dev.tomewisp.model.CancellationSignal, CompletableFuture<Void>> dispatchReady) {
        this(
                (actor, payload) -> new ToolResult.Success<>(
                        new RequestRuntime(agent, tools, () -> {})),
                sessions,
                contexts,
                events,
                gson,
                systemPrompt,
                dispatchReady);
    }

    public ServerAgentService(
            RequestRuntimeFactory runtimes,
            AgentSessionStore sessions,
            ContextProvider contexts,
            ServerGuideEvents events,
            Gson gson,
            String systemPrompt,
            Function<dev.tomewisp.model.CancellationSignal, CompletableFuture<Void>> dispatchReady) {
        this.runtimes = java.util.Objects.requireNonNull(runtimes, "runtimes");
        this.sessions = sessions;
        this.contexts = contexts;
        this.events = events;
        this.eventCodec = new ServerAgentEventCodec(gson);
        this.systemPrompt = systemPrompt;
        this.dispatchReady = dispatchReady;
    }

    public ToolResult<Accepted> ask(UUID sender, ServerAgentRequestPayload payload) {
        ToolResult<RequestRuntime> prepared = runtimes.create(sender, payload);
        if (prepared instanceof ToolResult.Failure<RequestRuntime> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        RequestRuntime runtime = ((ToolResult.Success<RequestRuntime>) prepared).value();
        Owner owner = new Owner(
                sender,
                payload.sessionId(),
                new dev.tomewisp.model.CancellationSignal(),
                runtime);
        if (active.putIfAbsent(payload.requestId(), owner) != null) {
            runtime.close().run();
            return new ToolResult.Failure<>("duplicate_request", "Request ID is already active");
        }
        dispatchReady.apply(owner.cancellation())
                .thenCompose(ignored -> {
                    if (!owns(payload.requestId(), owner) || owner.cancellation().isCancelled()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return contexts.capture(
                            sender,
                            runtime.tools().requiredContext(),
                            payload.requestId().toString());
                })
                .thenCompose(context -> {
                    if (context == null
                            || !owns(payload.requestId(), owner)
                            || owner.cancellation().isCancelled()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    AgentRequest request = new AgentRequest(
                            payload.requestId(),
                            sender,
                            payload.sessionId(),
                            payload.question(),
                            systemPrompt,
                            context,
                            payload.stream());
                    List<ModelMessage> restored = payload.history().stream()
                            .map(ServerAgentHistoryMessage::toModelMessage)
                            .toList();
                    return runtime.agent().askWithHistory(
                            request,
                            restored,
                            event -> publish(payload.requestId(), owner, event));
                })
                .exceptionally(throwable -> {
                    publishFailure(payload.requestId(), owner, throwable);
                    return null;
                });
        return new ToolResult.Success<>(new Accepted(payload.requestId(), payload.sessionId()));
    }

    public boolean cancel(UUID sender, UUID requestId) {
        Owner owner = active.get(requestId);
        if (owner == null || !owner.actorId().equals(sender)) {
            return false;
        }
        boolean cancelledBeforeCapture = owner.cancellation().cancel();
        boolean cancelledAgent = sessions.cancel(new AgentSessionKey(sender, owner.sessionId()));
        if (cancelledBeforeCapture || cancelledAgent) {
            publish(requestId, owner, new AgentEvent.Failed(
                    "agent_cancelled", "Agent request was cancelled"));
            return true;
        }
        return false;
    }

    public int disconnect(UUID sender) {
        long count = active.values().stream().filter(owner -> owner.actorId().equals(sender)).count();
        active.entrySet().removeIf(entry -> {
            if (entry.getValue().actorId().equals(sender)) {
                entry.getValue().cancellation().cancel();
                entry.getValue().runtime().close().run();
                return true;
            }
            return false;
        });
        sessions.clearActor(sender);
        return Math.toIntExact(count);
    }

    public int activeRequests() {
        return active.size();
    }

    private void publish(UUID requestId, Owner owner, AgentEvent event) {
        if (!owns(requestId, owner)) {
            return;
        }
        boolean terminal = event instanceof AgentEvent.FinalText || event instanceof AgentEvent.Failed;
        events.send(owner.actorId(), eventCodec.encode(requestId, event));
        if (terminal) {
            if (active.remove(requestId, owner)) {
                owner.runtime().close().run();
            }
        }
    }

    private void publishFailure(UUID requestId, Owner owner, Throwable throwable) {
        if (!active.remove(requestId, owner)) {
            return;
        }
        owner.runtime().close().run();
        Throwable cause = throwable;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        AgentEvent.Failed failed = new AgentEvent.Failed("server_agent_failure", message);
        events.send(owner.actorId(), eventCodec.encode(requestId, failed));
    }

    private boolean owns(UUID requestId, Owner owner) {
        return owner.equals(active.get(requestId));
    }

    public record Accepted(UUID requestId, String sessionId) {}

    public record RequestRuntime(
            GameGuideAgent agent, AgentToolExecutor tools, Runnable close) {
        public RequestRuntime {
            java.util.Objects.requireNonNull(agent, "agent");
            java.util.Objects.requireNonNull(tools, "tools");
            java.util.Objects.requireNonNull(close, "close");
        }
    }

    private record Owner(
            UUID actorId,
            String sessionId,
            dev.tomewisp.model.CancellationSignal cancellation,
            RequestRuntime runtime) {}
}
