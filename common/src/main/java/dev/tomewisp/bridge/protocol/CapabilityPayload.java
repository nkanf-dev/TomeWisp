package dev.tomewisp.bridge.protocol;

import java.util.List;

public record CapabilityPayload(
        int version, List<RemoteToolCapability> remoteTools, boolean serverModel) {
    public CapabilityPayload {
        BridgeProtocol.requireVersion(version);
        remoteTools = List.copyOf(remoteTools);
    }

    public record RemoteToolCapability(String id, String description, String inputSchemaJson) {
        public RemoteToolCapability {
            if (id == null || id.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException("Remote tool identity and description are required");
            }
            if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
                throw new IllegalArgumentException("Remote tool schema is required");
            }
        }
    }
}
