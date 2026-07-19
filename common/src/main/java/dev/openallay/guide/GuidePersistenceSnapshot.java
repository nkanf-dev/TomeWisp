package dev.openallay.guide;

import java.util.Objects;

public record GuidePersistenceSnapshot(
        State state,
        long submittedGeneration,
        long committedGeneration,
        GuideFailure failure) {
    public enum State {
        DISABLED,
        LOADING,
        SAVING,
        AVAILABLE,
        UNAVAILABLE
    }

    public GuidePersistenceSnapshot {
        Objects.requireNonNull(state, "state");
        if (submittedGeneration < 0
                || committedGeneration < 0
                || committedGeneration > submittedGeneration) {
            throw new IllegalArgumentException("persistence generations are invalid");
        }
        if (state == State.SAVING && submittedGeneration <= committedGeneration) {
            throw new IllegalArgumentException("saving state requires an uncommitted generation");
        }
        if (state == State.AVAILABLE && submittedGeneration != committedGeneration) {
            throw new IllegalArgumentException("available state requires all writes committed");
        }
        if (state == State.UNAVAILABLE && failure == null) {
            throw new IllegalArgumentException("unavailable persistence requires a failure");
        }
        if (state != State.UNAVAILABLE && failure != null) {
            throw new IllegalArgumentException("only unavailable persistence may expose a failure");
        }
    }

    public static GuidePersistenceSnapshot disabled() {
        return new GuidePersistenceSnapshot(State.DISABLED, 0, 0, null);
    }

    public static GuidePersistenceSnapshot loading() {
        return new GuidePersistenceSnapshot(State.LOADING, 0, 0, null);
    }

    public static GuidePersistenceSnapshot available(long generation) {
        return new GuidePersistenceSnapshot(State.AVAILABLE, generation, generation, null);
    }
}
