package dev.tomewisp.guide;

import java.util.List;

public record GuideSessionSnapshot(
        String sessionId,
        List<GuideMessage> messages,
        List<GuideRequestSnapshot> requests) {
    public GuideSessionSnapshot {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        messages = List.copyOf(messages);
        requests = List.copyOf(requests);
    }
}
