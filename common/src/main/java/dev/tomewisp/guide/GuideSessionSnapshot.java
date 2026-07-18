package dev.tomewisp.guide;

import dev.tomewisp.agent.context.ContextCheckpoint;
import java.util.List;

public record GuideSessionSnapshot(
        String sessionId,
        List<GuideMessage> messages,
        List<GuideRequestSnapshot> requests,
        List<ContextCheckpoint> checkpoints) {
    public GuideSessionSnapshot {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        messages = List.copyOf(messages);
        requests = List.copyOf(requests);
        checkpoints = List.copyOf(checkpoints);
    }

    public GuideSessionSnapshot(
            String sessionId,
            List<GuideMessage> messages,
            List<GuideRequestSnapshot> requests) {
        this(sessionId, messages, requests, List.of());
    }
}
