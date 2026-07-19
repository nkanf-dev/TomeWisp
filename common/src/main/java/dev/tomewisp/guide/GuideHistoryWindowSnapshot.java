package dev.tomewisp.guide;

import dev.tomewisp.guide.history.GuideHistoryCursor;

/** Immutable viewport/history metadata; request bodies remain in the session page only. */
public record GuideHistoryWindowSnapshot(
        long totalRequests,
        GuideHistoryCursor firstAvailable,
        GuideHistoryCursor lastAvailable,
        GuideHistoryCursor firstLoaded,
        GuideHistoryCursor lastLoaded,
        boolean hasEarlier,
        boolean hasLater,
        GuideHistoryPageState state,
        long generation,
        GuideFailure failure) {
    public GuideHistoryWindowSnapshot {
        if (totalRequests < 0 || generation < 0) {
            throw new IllegalArgumentException("history window counters are invalid");
        }
        java.util.Objects.requireNonNull(state, "state");
        if (state == GuideHistoryPageState.FAILED && failure == null
                || state != GuideHistoryPageState.FAILED && failure != null) {
            throw new IllegalArgumentException("history window failure state is inconsistent");
        }
        if (totalRequests == 0
                && (firstAvailable != null || lastAvailable != null
                        || firstLoaded != null || lastLoaded != null
                        || hasEarlier || hasLater)) {
            throw new IllegalArgumentException("empty history window has cursors");
        }
    }

    public static GuideHistoryWindowSnapshot disabled(long loadedRequests) {
        return new GuideHistoryWindowSnapshot(
                loadedRequests, null, null, null, null,
                false, false, GuideHistoryPageState.IDLE, 0, null);
    }
}
