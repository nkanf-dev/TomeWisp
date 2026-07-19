package dev.openallay.guide.history;

/** Viewport paging is independent from model-context budgeting. */
public record GuideHistoryPageRequest(
        GuideHistoryScope scope,
        String sessionId,
        Direction direction,
        GuideHistoryCursor cursor,
        int count) {
    public enum Direction { NEWEST, BEFORE, AFTER }

    public GuideHistoryPageRequest {
        java.util.Objects.requireNonNull(scope, "scope");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session ID");
        }
        java.util.Objects.requireNonNull(direction, "direction");
        if (direction == Direction.NEWEST && cursor != null
                || direction != Direction.NEWEST && cursor == null) {
            throw new IllegalArgumentException("history page cursor does not match direction");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("history page count must be positive");
        }
    }
}
