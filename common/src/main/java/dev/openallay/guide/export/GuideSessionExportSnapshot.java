package dev.openallay.guide.export;

import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideToolStatus;
import java.time.Instant;
import java.util.List;

/** Closed, credential-free, point-in-time projection for player-initiated export. */
public record GuideSessionExportSnapshot(
        String sessionId,
        List<Request> requests,
        Instant capturedAt) {
    public GuideSessionExportSnapshot {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid export session ID");
        }
        requests = List.copyOf(requests);
        java.util.Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public record Request(
            Instant createdAt,
            GuideRequestStatus status,
            String userMessage,
            List<Entry> timeline) {
        public Request {
            java.util.Objects.requireNonNull(createdAt, "createdAt");
            java.util.Objects.requireNonNull(status, "status");
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("export user message is blank");
            }
            timeline = List.copyOf(timeline);
        }
    }

    public sealed interface Entry permits Entry.Assistant, Entry.Tool {
        record Assistant(String text, boolean streaming) implements Entry {
            public Assistant { text = text == null ? "" : text; }
        }

        record Tool(String toolId, GuideToolStatus status) implements Entry {
            public Tool {
                if (toolId == null || toolId.isBlank()) {
                    throw new IllegalArgumentException("export Tool ID is blank");
                }
                java.util.Objects.requireNonNull(status, "status");
            }
        }
    }
}
