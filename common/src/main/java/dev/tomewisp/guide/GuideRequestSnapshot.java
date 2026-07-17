package dev.tomewisp.guide;

import dev.tomewisp.model.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GuideRequestSnapshot(
        UUID requestId,
        String sessionId,
        GuideTopology topology,
        String userMessage,
        List<GuideTimelineEntry> timeline,
        GuideRequestStatus status,
        List<GuideSource> sources,
        ModelUsage usage,
        Long retryAfterMillis,
        GuideFailure failure,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt) {
    public GuideRequestSnapshot {
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        java.util.Objects.requireNonNull(topology, "topology");
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        timeline = List.copyOf(timeline);
        for (int index = 0; index < timeline.size(); index++) {
            if (timeline.get(index).ordinal() != index) {
                throw new IllegalArgumentException("timeline ordinals must be contiguous");
            }
        }
        java.util.Objects.requireNonNull(status, "status");
        sources = List.copyOf(sources);
        java.util.Objects.requireNonNull(usage, "usage");
        if (retryAfterMillis != null && retryAfterMillis < 0) {
            throw new IllegalArgumentException("retryAfterMillis must not be negative");
        }
        java.util.Objects.requireNonNull(createdAt, "createdAt");
        java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static GuideRequestSnapshot start(
            UUID requestId,
            String sessionId,
            GuideTopology topology,
            String userMessage,
            Instant now) {
        return new GuideRequestSnapshot(
                requestId,
                sessionId,
                topology,
                userMessage,
                List.of(),
                GuideRequestStatus.PREPARING,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                now,
                now,
                null);
    }

    public boolean terminal() {
        return terminalAt != null;
    }

    public String assistantText() {
        for (int index = timeline.size() - 1; index >= 0; index--) {
            if (timeline.get(index) instanceof GuideTimelineEntry.Assistant assistant) {
                return assistant.text();
            }
        }
        return "";
    }

    public List<GuideToolActivity> tools() {
        return timeline.stream()
                .filter(GuideTimelineEntry.Tool.class::isInstance)
                .map(GuideTimelineEntry.Tool.class::cast)
                .map(GuideTimelineEntry.Tool::activity)
                .toList();
    }
}
