package dev.tomewisp.server;

import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import java.util.UUID;

@FunctionalInterface
public interface ServerGuideEvents {
    void send(UUID actorId, ServerAgentEventPayload event);
}
