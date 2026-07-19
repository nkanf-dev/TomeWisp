package dev.openallay.bridge.protocol;

import java.util.UUID;

/** One hash-checked transport chunk of a complete server Agent event payload. */
public record ServerAgentEventChunkPayload(
        int version,
        UUID requestId,
        UUID eventId,
        int index,
        int total,
        String contentHash,
        String base64Data) {
    public ServerAgentEventChunkPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(eventId, "eventId");
        if (index < 0 || total <= 0 || index >= total) {
            throw new IllegalArgumentException("Invalid server Agent event chunk position");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid server Agent event SHA-256 hash");
        }
        if (base64Data == null) {
            throw new IllegalArgumentException("Server Agent event chunk data is required");
        }
        try {
            java.util.Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Server Agent event chunk is not valid base64", failure);
        }
    }

    public RemoteToolResultChunkPayload asRemoteChunk() {
        return new RemoteToolResultChunkPayload(
                version, eventId, index, total, contentHash, base64Data);
    }

    public static ServerAgentEventChunkPayload from(
            UUID requestId, RemoteToolResultChunkPayload chunk) {
        return new ServerAgentEventChunkPayload(
                chunk.version(), requestId, chunk.correlationId(), chunk.index(), chunk.total(),
                chunk.contentHash(), chunk.base64Data());
    }
}
