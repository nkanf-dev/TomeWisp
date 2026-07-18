package dev.tomewisp.guide;

import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface GuideLocalEndpoint {
    Set<ContextCapability> requiredContext();

    default String defaultProfileId() {
        return "default";
    }

    default List<GuideClientModelProfile> profiles() {
        return List.of(new GuideClientModelProfile(
                defaultProfileId(),
                "Client model",
                true,
                true,
                "default",
                null));
    }

    default Optional<GuideClientModelProfile> profile(String profileId) {
        return profiles().stream().filter(profile -> profile.id().equals(profileId)).findFirst();
    }

    default Set<ContextCapability> requiredContext(String profileId) {
        GuideClientModelProfile profile = profile(profileId).orElseThrow(() ->
                new GuideModelProfileException(
                        "model_not_configured", "The selected client model profile does not exist"));
        if (!profile.available()) {
            throw new GuideModelProfileException(profile.failure().code(), profile.failure().message());
        }
        return requiredContext();
    }

    CompletableFuture<AgentResult> ask(
            UUID actor,
            String sessionId,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events);

    default CompletableFuture<AgentResult> ask(
            String profileId,
            UUID actor,
            String sessionId,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        if (!defaultProfileId().equals(profileId)) {
            return CompletableFuture.failedFuture(new GuideModelProfileException(
                    "model_not_configured", "The selected client model profile does not exist"));
        }
        return ask(actor, sessionId, requestId, question, context, events);
    }

    boolean cancel(UUID actor, String sessionId);

    void clearSession(UUID actor, String sessionId);

    void clearActor(UUID actor);

    default void hydrateSession(
            UUID actor,
            String sessionId,
            List<GuideMessage> messages,
            List<ContextCheckpoint> checkpoints) {}
}
