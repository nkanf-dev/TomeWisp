package dev.openallay.bridge.protocol;

import java.util.UUID;

/** One hash-checked chunk of a normalized player-client Tool result. */
public record ClientToolResultChunkPayload(
        int version,
        UUID requestId,
        UUID invocationId,
        int index,
        int total,
        String contentHash,
        String base64Data) {
    public ClientToolResultChunkPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        if (index < 0 || total <= 0 || index >= total) {
            throw new IllegalArgumentException("Invalid client Tool result chunk position");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid client Tool result SHA-256 hash");
        }
        if (base64Data == null) {
            throw new IllegalArgumentException("Client Tool result chunk data is required");
        }
        try {
            java.util.Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Client Tool result chunk is not valid base64", failure);
        }
    }

    public RemoteToolResultChunkPayload asRemoteChunk() {
        return new RemoteToolResultChunkPayload(
                version, invocationId, index, total, contentHash, base64Data);
    }

    public static ClientToolResultChunkPayload from(
            UUID requestId, RemoteToolResultChunkPayload chunk) {
        return new ClientToolResultChunkPayload(
                chunk.version(), requestId, chunk.correlationId(), chunk.index(), chunk.total(),
                chunk.contentHash(), chunk.base64Data());
    }
}
