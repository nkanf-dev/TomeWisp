package dev.tomewisp.guide.ui;

import com.google.gson.JsonObject;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Player-facing tool detail plus an optional debug-only technical projection. */
public record GuideToolDetailView(
        String titleKey,
        GuideToolStatus status,
        List<GuideDetailCard> cards,
        List<GuideToolMessage> narration,
        Optional<Debug> debug) {
    public GuideToolDetailView {
        if (titleKey == null || titleKey.isBlank()) {
            throw new IllegalArgumentException("titleKey must not be blank");
        }
        Objects.requireNonNull(status, "status");
        cards = List.copyOf(cards);
        narration = List.copyOf(narration);
        debug = Objects.requireNonNull(debug, "debug");
    }

    public record Debug(
            String invocationId,
            String toolId,
            List<GuideSource> sources,
            JsonObject normalized,
            String validationDiagnostic) {
        public Debug {
            if (invocationId == null || invocationId.isBlank()
                    || toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("debug identity must not be blank");
            }
            sources = List.copyOf(sources);
            normalized = normalized == null ? null : normalized.deepCopy();
            validationDiagnostic = validationDiagnostic == null ? "" : validationDiagnostic;
        }

        @Override
        public JsonObject normalized() {
            return normalized == null ? null : normalized.deepCopy();
        }
    }
}
