package dev.openallay.bridge.protocol;

import java.util.UUID;

/** Cancels one reverse client Tool invocation without cancelling another request. */
public record ClientToolCancelPayload(int version, UUID requestId, UUID invocationId) {
    public ClientToolCancelPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
    }
}
