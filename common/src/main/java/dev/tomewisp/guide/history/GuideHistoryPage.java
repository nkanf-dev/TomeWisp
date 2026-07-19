package dev.tomewisp.guide.history;

import dev.tomewisp.guide.GuideRequestSnapshot;
import java.util.List;

public record GuideHistoryPage(
        String sessionId,
        List<GuideRequestSnapshot> requests,
        GuideHistoryCursor first,
        GuideHistoryCursor last,
        boolean hasEarlier,
        boolean hasLater) {
    public GuideHistoryPage {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session ID");
        }
        requests = List.copyOf(requests);
        if (requests.isEmpty() ? first != null || last != null : first == null || last == null) {
            throw new IllegalArgumentException("history page cursor metadata is inconsistent");
        }
    }
}
