package dev.openallay.server;

import dev.openallay.bridge.protocol.ServerAgentEventPayload;
import java.util.UUID;

@FunctionalInterface
public interface ServerGuideEvents {
    void send(UUID actorId, ServerAgentEventPayload event);
}
