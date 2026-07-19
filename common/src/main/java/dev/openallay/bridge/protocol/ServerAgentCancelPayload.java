package dev.openallay.bridge.protocol;

import java.util.UUID;

public record ServerAgentCancelPayload(int version, UUID requestId) {
    public ServerAgentCancelPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
    }
}
