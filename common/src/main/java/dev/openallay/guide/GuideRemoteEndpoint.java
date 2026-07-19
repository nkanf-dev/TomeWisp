package dev.openallay.guide;

import dev.openallay.agent.AgentEvent;
import dev.openallay.model.ModelMessage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface GuideRemoteEndpoint {
    boolean serverModelAvailable();

    boolean serverToolsAvailable();

    default Optional<GuideContextSpec> contextSpec() {
        return Optional.empty();
    }

    boolean ask(
            UUID requestId, String sessionId, String question, Consumer<AgentEvent> events);

    default boolean ask(
            UUID requestId,
            String sessionId,
            String question,
            List<GuideMessage> history,
            Consumer<AgentEvent> events) {
        return ask(requestId, sessionId, question, events);
    }

    default boolean askWithContext(
            UUID requestId,
            String sessionId,
            String question,
            List<ModelMessage> history,
            Consumer<AgentEvent> events) {
        return ask(requestId, sessionId, question, events);
    }

    boolean cancel(UUID requestId);

    void disconnect();
}
