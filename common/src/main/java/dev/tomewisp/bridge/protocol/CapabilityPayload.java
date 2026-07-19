package dev.tomewisp.bridge.protocol;

import java.util.List;

public record CapabilityPayload(
        int version,
        List<RemoteToolCapability> remoteTools,
        boolean serverModel,
        int serverContextWindowTokens,
        int serverMaxOutputTokens,
        int serverPromptAndToolTokens,
        String serverCanonicalModelId) {
    public CapabilityPayload {
        BridgeProtocol.requireVersion(version);
        remoteTools = List.copyOf(remoteTools);
        if (serverModel) {
            if (serverCanonicalModelId == null || serverCanonicalModelId.isBlank()) {
                throw new IllegalArgumentException("Server model context capability is required");
            }
            dev.tomewisp.agent.context.ContextBudget budget =
                    new dev.tomewisp.agent.context.ContextBudget(
                            serverContextWindowTokens, serverMaxOutputTokens);
            if (serverPromptAndToolTokens < 0
                    || serverPromptAndToolTokens >= budget.inputTokens()) {
                throw new IllegalArgumentException("Invalid server model prompt/tool reservation");
            }
        } else if (serverContextWindowTokens != 0 || serverMaxOutputTokens != 0
                || serverPromptAndToolTokens != 0
                || serverCanonicalModelId == null || !serverCanonicalModelId.isEmpty()) {
            throw new IllegalArgumentException("Unavailable server model cannot advertise a budget");
        }
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
