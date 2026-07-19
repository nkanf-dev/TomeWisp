package dev.openallay.agent.tool;

import com.google.gson.JsonObject;

public record AgentToolResult(
        String toolId, JsonObject normalized, JsonObject modelPayload, boolean failure) {
    public AgentToolResult(String toolId, JsonObject normalized, boolean failure) {
        this(toolId, normalized, normalized, failure);
    }

    public AgentToolResult {
        normalized = normalized.deepCopy();
        modelPayload = modelPayload.deepCopy();
    }

    @Override
    public JsonObject normalized() {
        return normalized.deepCopy();
    }

    @Override
    public JsonObject modelPayload() {
        return modelPayload.deepCopy();
    }
}
