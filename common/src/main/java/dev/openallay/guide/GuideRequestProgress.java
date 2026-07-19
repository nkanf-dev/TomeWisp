package dev.openallay.guide;

import java.time.Instant;
import java.util.Objects;

/** Immutable request progress containing no provider, prompt, argument, or response content. */
public record GuideRequestProgress(
        GuideRequestPhase phase,
        Instant requestStartedAt,
        Instant phaseStartedAt,
        Instant lastProgressAt,
        int attempt,
        Instant retryAt,
        Instant deadlineAt) {
    public GuideRequestProgress {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(requestStartedAt, "requestStartedAt");
        Objects.requireNonNull(phaseStartedAt, "phaseStartedAt");
        Objects.requireNonNull(lastProgressAt, "lastProgressAt");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        if (phaseStartedAt.isBefore(requestStartedAt)
                || lastProgressAt.isBefore(phaseStartedAt)) {
            throw new IllegalArgumentException("request progress timestamps are out of order");
        }
        if (retryAt != null && retryAt.isBefore(lastProgressAt)) {
            throw new IllegalArgumentException("retryAt must not precede lastProgressAt");
        }
        if (deadlineAt != null && deadlineAt.isBefore(requestStartedAt)) {
            throw new IllegalArgumentException("deadlineAt must not precede requestStartedAt");
        }
    }

    public static GuideRequestProgress start(Instant now) {
        return new GuideRequestProgress(
                GuideRequestPhase.PREPARING, now, now, now, 0, null, null);
    }

    public GuideRequestProgress advance(
            GuideRequestPhase nextPhase,
            Instant observedAt,
            int nextAttempt,
            Instant nextRetryAt,
            Instant nextDeadlineAt) {
        Objects.requireNonNull(nextPhase, "nextPhase");
        Objects.requireNonNull(observedAt, "observedAt");
        Instant monotonic = observedAt.isBefore(lastProgressAt) ? lastProgressAt : observedAt;
        Instant nextPhaseStarted = nextPhase == phase ? phaseStartedAt : monotonic;
        return new GuideRequestProgress(
                nextPhase,
                requestStartedAt,
                nextPhaseStarted,
                monotonic,
                nextAttempt,
                nextRetryAt,
                nextDeadlineAt);
    }

    public GuideRequestProgress advance(GuideRequestPhase nextPhase, Instant observedAt) {
        return advance(nextPhase, observedAt, attempt, retryAt, deadlineAt);
    }
}
