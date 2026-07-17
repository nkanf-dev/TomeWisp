package dev.tomewisp.guide;

import dev.tomewisp.agent.AgentEvent;
import java.util.UUID;
import java.util.function.Consumer;

public interface GuideRemoteEndpoint {
    boolean serverModelAvailable();

    boolean serverToolsAvailable();

    boolean ask(
            UUID requestId, String sessionId, String question, Consumer<AgentEvent> events);

    boolean cancel(UUID requestId);

    void disconnect();
}
