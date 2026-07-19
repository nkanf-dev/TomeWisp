package dev.tomewisp.bridge.protocol;

import java.util.UUID;

public record ServerAgentRequestChunkPayload(
        int version,
        UUID requestId,
        int index,
        int total,
        String contentHash,
        String base64Data) {
    public ServerAgentRequestChunkPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (index < 0 || total <= 0 || index >= total) {
            throw new IllegalArgumentException("Invalid server Agent request chunk position");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 content hash");
        }
        if (base64Data == null) {
            throw new IllegalArgumentException("Chunk data is required");
        }
        try {
            java.util.Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Chunk data is not valid base64", failure);
        }
    }
}
