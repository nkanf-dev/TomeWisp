package dev.tomewisp.guide;

import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface GuideLocalEndpoint {
    Set<ContextCapability> requiredContext();

    CompletableFuture<AgentResult> ask(
            UUID actor,
            String sessionId,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events);

    boolean cancel(UUID actor, String sessionId);

    void clearSession(UUID actor, String sessionId);

    void clearActor(UUID actor);

    default void hydrateSession(
            UUID actor,
            String sessionId,
            List<GuideMessage> messages,
            List<ContextCheckpoint> checkpoints) {}
}
