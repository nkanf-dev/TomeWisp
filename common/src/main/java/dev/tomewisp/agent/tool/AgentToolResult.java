package dev.tomewisp.agent.tool;

import com.google.gson.JsonObject;

public record AgentToolResult(String toolId, JsonObject normalized, boolean failure) {
    public AgentToolResult {
        normalized = normalized.deepCopy();
    }

    @Override
    public JsonObject normalized() {
        return normalized.deepCopy();
    }
}
