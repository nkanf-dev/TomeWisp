package dev.tomewisp.bridge.protocol;

import java.util.UUID;

public record RemoteCancelPayload(int version, UUID correlationId) {
    public RemoteCancelPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(correlationId, "correlationId");
    }
}
