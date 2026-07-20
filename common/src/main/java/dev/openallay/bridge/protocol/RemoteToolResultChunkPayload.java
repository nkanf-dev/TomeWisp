package dev.openallay.bridge.protocol;

import java.util.UUID;

public record RemoteToolResultChunkPayload(
        int version,
        UUID correlationId,
        int index,
        int total,
        String viewId,
        String contentHash,
        String base64Data) {
    public RemoteToolResultChunkPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        if (index < 0 || total <= 0 || index >= total) {
            throw new IllegalArgumentException("Invalid result chunk position");
        }
        viewId = BridgeViewIdentity.require(viewId);
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

    public RemoteToolResultChunkPayload(
            int version,
            UUID correlationId,
            int index,
            int total,
            String contentHash,
            String base64Data) {
        this(
                version,
                correlationId,
                index,
                total,
                BridgeViewIdentity.forOperation(correlationId),
                contentHash,
                base64Data);
    }
}
