package dev.tomewisp.guide.history;

import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.model.ModelMessage;
import java.util.List;

/** Bounded, provider-neutral Agent context; never a GUI page projection. */
public record GuideHistoryContextSeed(
        String sessionId,
        List<ModelMessage> messages,
        List<ContextCheckpoint> checkpoints,
        int estimatedTokens,
        GuideHistoryCursor oldestIncluded) {
    public GuideHistoryContextSeed {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session ID");
        }
        messages = List.copyOf(messages);
        checkpoints = List.copyOf(checkpoints);
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("context estimate must not be negative");
        }
        if (messages.isEmpty() != (oldestIncluded == null)) {
            throw new IllegalArgumentException("context cursor is inconsistent");
        }
    }
}
