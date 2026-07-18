package dev.tomewisp.guide;

import dev.tomewisp.agent.AgentEvent;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface GuideRemoteEndpoint {
    boolean serverModelAvailable();

    boolean serverToolsAvailable();

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

    boolean cancel(UUID requestId);

    void disconnect();
}
