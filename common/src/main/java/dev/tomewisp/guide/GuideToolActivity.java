package dev.tomewisp.guide;

import com.google.gson.JsonObject;
import java.util.List;

public record GuideToolActivity(
        int index,
        String toolId,
        GuideToolStatus status,
        JsonObject normalized,
        List<GuideSource> sources) {
    public GuideToolActivity {
        if (index < 0 || toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool activity identity is invalid");
        }
        java.util.Objects.requireNonNull(status, "status");
        normalized = normalized == null ? null : normalized.deepCopy();
        sources = List.copyOf(sources);
    }

    @Override
    public JsonObject normalized() {
        return normalized == null ? null : normalized.deepCopy();
    }
}
