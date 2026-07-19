package dev.openallay.guide.ui;

import dev.openallay.guide.GuideRequestPhase;
import dev.openallay.guide.GuideRequestProgress;
import java.time.Instant;
import java.util.Objects;

/** Redacted player-facing projection for the fixed request progress strip. */
public record GuideUiProgress(
        GuideRequestPhase phase,
        String activityTranslationKey,
        Instant requestStartedAt,
        Instant phaseStartedAt,
        Instant lastProgressAt,
        int attempt,
        Instant retryAt,
        Instant deadlineAt) {
    public GuideUiProgress {
        Objects.requireNonNull(phase, "phase");
        if (activityTranslationKey == null || activityTranslationKey.isBlank()) {
            throw new IllegalArgumentException("progress translation key must not be blank");
        }
        Objects.requireNonNull(requestStartedAt, "requestStartedAt");
        Objects.requireNonNull(phaseStartedAt, "phaseStartedAt");
        Objects.requireNonNull(lastProgressAt, "lastProgressAt");
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
    }

    public static GuideUiProgress from(GuideRequestProgress progress) {
        Objects.requireNonNull(progress, "progress");
        return new GuideUiProgress(
                progress.phase(),
                switch (progress.phase()) {
                    case PREPARING -> "screen.openallay.progress.preparing";
                    case CONTEXT_LOADING -> "screen.openallay.progress.context_loading";
                    case COMPACTING -> "screen.openallay.progress.compacting";
                    case ENDPOINT_WAIT -> "screen.openallay.progress.endpoint_wait";
                    case MODEL_WAIT -> "screen.openallay.progress.model_wait";
                    case RESPONSE_STREAMING -> "screen.openallay.progress.streaming";
                    case TOOL_WAIT -> "screen.openallay.progress.tool_wait";
                    case COMPLETING -> "screen.openallay.progress.completing";
                },
                progress.requestStartedAt(),
                progress.phaseStartedAt(),
                progress.lastProgressAt(),
                progress.attempt(),
                progress.retryAt(),
                progress.deadlineAt());
    }
}
