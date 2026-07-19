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
        Instant terminalAt,
        GuideModelSelection modelSelection,
        GuideRequestProgress progress) {
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
        java.util.Objects.requireNonNull(modelSelection, "modelSelection");
        java.util.Objects.requireNonNull(progress, "progress");
        if (!progress.requestStartedAt().equals(createdAt)) {
            throw new IllegalArgumentException("request progress must share createdAt");
        }
        if (modelSelection.modelMode() == GuideModelMode.SERVER
                && topology != GuideTopology.SERVER) {
            throw new IllegalArgumentException("server model selection requires server topology");
        }
        if (modelSelection.modelMode() == GuideModelMode.CLIENT
                && topology == GuideTopology.SERVER) {
            throw new IllegalArgumentException("client model selection cannot use server topology");
        }
    }

    public GuideRequestSnapshot(
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
            Instant terminalAt,
            GuideModelSelection modelSelection) {
        this(
                requestId,
                sessionId,
                topology,
                userMessage,
                timeline,
                status,
                sources,
                usage,
                retryAfterMillis,
                failure,
                createdAt,
                updatedAt,
                terminalAt,
                modelSelection,
                legacyProgress(status, retryAfterMillis, createdAt, updatedAt));
    }

    public GuideRequestSnapshot(
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
        this(
                requestId,
                sessionId,
                topology,
                userMessage,
                timeline,
                status,
                sources,
                usage,
                retryAfterMillis,
                failure,
                createdAt,
                updatedAt,
                terminalAt,
                topology == GuideTopology.SERVER
                        ? GuideModelSelection.server()
                        : GuideModelSelection.client("default"),
                legacyProgress(status, retryAfterMillis, createdAt, updatedAt));
    }

    public static GuideRequestSnapshot start(
            UUID requestId,
            String sessionId,
            GuideTopology topology,
            String userMessage,
            Instant now) {
        return start(
                requestId,
                sessionId,
                topology,
                userMessage,
                now,
                topology == GuideTopology.SERVER
                        ? GuideModelSelection.server()
                        : GuideModelSelection.client("default"));
    }

    public static GuideRequestSnapshot start(
            UUID requestId,
            String sessionId,
            GuideTopology topology,
            String userMessage,
            Instant now,
            GuideModelSelection modelSelection) {
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
                null,
                modelSelection,
                GuideRequestProgress.start(now));
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

    private static GuideRequestProgress legacyProgress(
            GuideRequestStatus status,
            Long retryAfterMillis,
            Instant createdAt,
            Instant updatedAt) {
        GuideRequestPhase phase = switch (status) {
            case PREPARING -> GuideRequestPhase.PREPARING;
            case CONTEXT_LOADING -> GuideRequestPhase.CONTEXT_LOADING;
            case COMPACTING -> GuideRequestPhase.COMPACTING;
            case RATE_LIMITED -> GuideRequestPhase.ENDPOINT_WAIT;
            case MODEL_WAIT -> GuideRequestPhase.MODEL_WAIT;
            case TOOL_WAIT -> GuideRequestPhase.TOOL_WAIT;
            case COMPLETING, COMPLETED, FAILED, CANCELLED, INTERRUPTED ->
                    GuideRequestPhase.COMPLETING;
        };
        Instant monotonicUpdated = updatedAt.isBefore(createdAt) ? createdAt : updatedAt;
        Instant retryAt = retryAfterMillis == null
                ? null
                : monotonicUpdated.plusMillis(retryAfterMillis);
        return new GuideRequestProgress(
                phase,
                createdAt,
                monotonicUpdated,
                monotonicUpdated,
                0,
                retryAt,
                null);
    }
}
