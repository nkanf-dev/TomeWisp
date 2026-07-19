package dev.openallay.guide.history;

import dev.openallay.guide.GuideModelSelection;
import java.time.Instant;
import java.util.List;

/** Body-free startup projection for one player/world-or-server partition. */
public record GuideHistoryMetadata(
        GuideHistoryScope scope,
        String selectedSession,
        List<Session> sessions,
        Instant updatedAt) {
    public GuideHistoryMetadata {
        java.util.Objects.requireNonNull(scope, "scope");
        if (selectedSession == null || selectedSession.isBlank()) {
            throw new IllegalArgumentException("selected session is required");
        }
        sessions = List.copyOf(sessions);
        if (sessions.stream().noneMatch(value -> value.sessionId().equals(selectedSession))) {
            throw new IllegalArgumentException("selected session is absent from metadata");
        }
        java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public record Session(
            String sessionId,
            int ordinal,
            GuideModelSelection modelSelection,
            long requestCount,
            GuideHistoryCursor first,
            GuideHistoryCursor last) {
        public Session {
            if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid session ID");
            }
            if (ordinal < 0 || requestCount < 0) {
                throw new IllegalArgumentException("session metadata count is invalid");
            }
            java.util.Objects.requireNonNull(modelSelection, "modelSelection");
            if (requestCount == 0 ? first != null || last != null : first == null || last == null) {
                throw new IllegalArgumentException("session cursor metadata is inconsistent");
            }
        }
    }
}
