package dev.openallay.agent.tool;

import com.google.gson.JsonObject;
import dev.openallay.resource.projection.NormalizedResultModelProjector;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public record AgentToolResult(
        String toolId,
        JsonObject normalized,
        ModelToolResultView modelView,
        ToolUiReference uiReference,
        ToolResultDiagnostics diagnostics,
        boolean failure) {
    public AgentToolResult {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId is required");
        }
        normalized = Objects.requireNonNull(normalized, "normalized").deepCopy();
        Objects.requireNonNull(modelView, "modelView");
        Objects.requireNonNull(uiReference, "uiReference");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public AgentToolResult(String toolId, JsonObject normalized, boolean failure) {
        this(toolId, normalized, NormalizedResultModelProjector.project(normalized),
                ToolUiReference.none(), diagnostics(normalized), failure);
    }

    @Override
    public JsonObject normalized() {
        return normalized.deepCopy();
    }

    public AgentToolResult withToolId(String replacement) {
        return new AgentToolResult(replacement, normalized, modelView, uiReference, diagnostics, failure);
    }

    private static ToolResultDiagnostics diagnostics(JsonObject normalized) {
        int bytes = normalized.toString().getBytes(StandardCharsets.UTF_8).length;
        ModelToolResultView view = NormalizedResultModelProjector.project(normalized);
        return new ToolResultDiagnostics(bytes, view.estimatedCharacters(), "legacy", Instant.now());
    }
}
