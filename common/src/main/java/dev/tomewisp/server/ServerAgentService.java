package dev.tomewisp.server;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentRequest;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerAgentService {
    @FunctionalInterface
    public interface ContextProvider {
        CompletableFuture<ToolInvocationContext> capture(
                UUID actorId, Set<ContextCapability> capabilities, String correlationId);
    }

    private final GameGuideAgent agent;
    private final AgentToolExecutor tools;
    private final AgentSessionStore sessions;
    private final ContextProvider contexts;
    private final ServerGuideEvents events;
    private final Gson gson;
    private final String systemPrompt;
    private final Map<UUID, Owner> active = new ConcurrentHashMap<>();

    public ServerAgentService(
            GameGuideAgent agent,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            ContextProvider contexts,
            ServerGuideEvents events,
            Gson gson,
            String systemPrompt) {
        this.agent = agent;
        this.tools = tools;
        this.sessions = sessions;
        this.contexts = contexts;
        this.events = events;
        this.gson = gson;
        this.systemPrompt = systemPrompt;
    }

    public ToolResult<Accepted> ask(UUID sender, ServerAgentRequestPayload payload) {
        Owner owner = new Owner(sender, payload.sessionId());
        if (active.putIfAbsent(payload.requestId(), owner) != null) {
            return new ToolResult.Failure<>("duplicate_request", "Request ID is already active");
        }
        contexts.capture(sender, tools.requiredContext(), payload.requestId().toString())
                .thenCompose(context -> {
                    if (!owns(payload.requestId(), owner)) {
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
                    return agent.ask(request, event -> publish(payload.requestId(), owner, event));
                })
                .exceptionally(throwable -> {
                    publishFailure(payload.requestId(), owner, throwable);
                    return null;
                });
        return new ToolResult.Success<>(new Accepted(payload.requestId(), payload.sessionId()));
    }

    public boolean cancel(UUID sender, UUID requestId) {
        Owner owner = active.get(requestId);
        return owner != null
                && owner.actorId().equals(sender)
                && sessions.cancel(new AgentSessionKey(sender, owner.sessionId()));
    }

    public int disconnect(UUID sender) {
        long count = active.values().stream().filter(owner -> owner.actorId().equals(sender)).count();
        active.entrySet().removeIf(entry -> entry.getValue().actorId().equals(sender));
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
        String type = switch (event) {
            case AgentEvent.StateChanged ignored -> "state";
            case AgentEvent.ModelProgress ignored -> "model_progress";
            case AgentEvent.ToolStarted ignored -> "tool_started";
            case AgentEvent.ToolCompleted ignored -> "tool_completed";
            case AgentEvent.FinalText ignored -> "final_text";
            case AgentEvent.Failed ignored -> "failed";
        };
        events.send(owner.actorId(), new ServerAgentEventPayload(
                BridgeProtocol.VERSION, requestId, type, gson.toJson(event), terminal));
        if (terminal) {
            active.remove(requestId, owner);
        }
    }

    private void publishFailure(UUID requestId, Owner owner, Throwable throwable) {
        if (!active.remove(requestId, owner)) {
            return;
        }
        Throwable cause = throwable;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        AgentEvent.Failed failed = new AgentEvent.Failed("server_agent_failure", message);
        events.send(owner.actorId(), new ServerAgentEventPayload(
                BridgeProtocol.VERSION, requestId, "failed", gson.toJson(failed), true));
    }

    private boolean owns(UUID requestId, Owner owner) {
        return owner.equals(active.get(requestId));
    }

    public record Accepted(UUID requestId, String sessionId) {}
    private record Owner(UUID actorId, String sessionId) {}
}
