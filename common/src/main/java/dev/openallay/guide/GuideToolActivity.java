package dev.openallay.guide;

import com.google.gson.JsonObject;
import java.util.List;

public record GuideToolActivity(
        String invocationId,
        int index,
        String toolId,
        GuideToolStatus status,
        JsonObject normalized,
        List<GuideToolMessage> presentationMessages,
        List<GuideSource> sources) {
    public GuideToolActivity {
        if (invocationId == null || invocationId.isBlank()
                || index < 0 || toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool activity identity is invalid");
        }
        java.util.Objects.requireNonNull(status, "status");
        normalized = normalized == null ? null : normalized.deepCopy();
        presentationMessages = List.copyOf(presentationMessages);
        sources = List.copyOf(sources);
    }

    @Override
    public JsonObject normalized() {
        return normalized == null ? null : normalized.deepCopy();
    }
}
