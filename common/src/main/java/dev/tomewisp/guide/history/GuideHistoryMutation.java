package dev.tomewisp.guide.history;

import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Closed set of minimum durable changes accepted by schema 5. */
public sealed interface GuideHistoryMutation permits
        GuideHistoryMutation.UpsertPartition,
        GuideHistoryMutation.UpsertSession,
        GuideHistoryMutation.UpsertRequest,
        GuideHistoryMutation.UpsertMessage,
        GuideHistoryMutation.UpsertTimelineEntry,
        GuideHistoryMutation.ReplaceRequestSources,
        GuideHistoryMutation.UpsertCheckpoint,
        GuideHistoryMutation.DeleteSession,
        GuideHistoryMutation.ClearSession {

    record UpsertPartition(String selectedSession, Instant updatedAt)
            implements GuideHistoryMutation {
        public UpsertPartition {
            if (selectedSession == null || selectedSession.isBlank()) {
                throw new IllegalArgumentException("selected session is required");
            }
            java.util.Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record UpsertSession(
            String sessionId, int ordinal, GuideModelSelection modelSelection)
            implements GuideHistoryMutation {
        public UpsertSession {
            requireSession(sessionId);
            if (ordinal < 0) throw new IllegalArgumentException("session ordinal is invalid");
            java.util.Objects.requireNonNull(modelSelection, "modelSelection");
        }
    }

    record UpsertRequest(long sequence, GuideRequestSnapshot request)
            implements GuideHistoryMutation {
        public UpsertRequest {
            if (sequence < 0) throw new IllegalArgumentException("request sequence is invalid");
            java.util.Objects.requireNonNull(request, "request");
        }
    }

    record UpsertMessage(String sessionId, int ordinal, GuideMessage message)
            implements GuideHistoryMutation {
        public UpsertMessage {
            requireSession(sessionId);
            if (ordinal < 0) throw new IllegalArgumentException("message ordinal is invalid");
            java.util.Objects.requireNonNull(message, "message");
        }
    }

    record UpsertTimelineEntry(UUID requestId, GuideTimelineEntry entry)
            implements GuideHistoryMutation {
        public UpsertTimelineEntry {
            java.util.Objects.requireNonNull(requestId, "requestId");
            java.util.Objects.requireNonNull(entry, "entry");
        }
    }

    record ReplaceRequestSources(UUID requestId, List<GuideSource> sources)
            implements GuideHistoryMutation {
        public ReplaceRequestSources {
            java.util.Objects.requireNonNull(requestId, "requestId");
            sources = List.copyOf(sources);
        }
    }

    record UpsertCheckpoint(
            String sessionId, int ordinal, ContextCheckpoint checkpoint)
            implements GuideHistoryMutation {
        public UpsertCheckpoint {
            requireSession(sessionId);
            if (ordinal < 0) throw new IllegalArgumentException("checkpoint ordinal is invalid");
            java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }

    record DeleteSession(String sessionId) implements GuideHistoryMutation {
        public DeleteSession { requireSession(sessionId); }
    }

    record ClearSession(String sessionId) implements GuideHistoryMutation {
        public ClearSession { requireSession(sessionId); }
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session ID");
        }
    }
}
