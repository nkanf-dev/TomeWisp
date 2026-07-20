package dev.openallay.guide;

import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import java.util.List;

public record GuideToolActivity(
        String invocationId,
        int index,
        String toolId,
        GuideToolStatus status,
        JsonObject normalized,
        ToolUiReference uiReference,
        ToolResultDiagnostics diagnostics,
        List<GuideToolMessage> presentationMessages,
        List<GuideSource> sources) {
    public GuideToolActivity {
        if (invocationId == null || invocationId.isBlank()
                || index < 0 || toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool activity identity is invalid");
        }
        java.util.Objects.requireNonNull(status, "status");
        normalized = normalized == null ? null : normalized.deepCopy();
        uiReference = java.util.Objects.requireNonNull(uiReference, "uiReference");
        diagnostics = java.util.Objects.requireNonNull(diagnostics, "diagnostics");
        presentationMessages = List.copyOf(presentationMessages);
        sources = List.copyOf(sources);
    }

    @Override
    public JsonObject normalized() {
        return normalized == null ? null : normalized.deepCopy();
    }

    public GuideToolActivity(
            String invocationId,
            int index,
            String toolId,
            GuideToolStatus status,
            JsonObject normalized,
            List<GuideToolMessage> presentationMessages,
            List<GuideSource> sources) {
        this(
                invocationId,
                index,
                toolId,
                status,
                normalized,
                ToolUiReference.none(),
                ToolResultDiagnostics.none(),
                presentationMessages,
                sources);
    }
}
