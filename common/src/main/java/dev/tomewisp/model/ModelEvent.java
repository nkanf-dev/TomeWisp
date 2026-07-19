package dev.tomewisp.model;

import com.google.gson.JsonObject;
import java.util.Objects;

public sealed interface ModelEvent
        permits ModelEvent.TextDelta,
                ModelEvent.ReasoningDelta,
                ModelEvent.ToolUseComplete,
                ModelEvent.UsageUpdate,
                ModelEvent.AttemptStarted,
                ModelEvent.ResponseStarted,
                ModelEvent.RateLimited,
                ModelEvent.MessageComplete,
                ModelFailure {
    record TextDelta(String text) implements ModelEvent {
        public TextDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    record ReasoningDelta(String text) implements ModelEvent {
        public ReasoningDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    record ToolUseComplete(String id, String name, JsonObject input) implements ModelEvent {
        public ToolUseComplete {
            input = Objects.requireNonNull(input, "input").deepCopy();
        }

        @Override
        public JsonObject input() {
            return input.deepCopy();
        }
    }

    record UsageUpdate(ModelUsage usage) implements ModelEvent {}

    /** Redacted lifecycle boundary. The relative attempt budget may be unavailable. */
    record AttemptStarted(int attempt, Long attemptTimeoutMillis) implements ModelEvent {
        public AttemptStarted {
            if (attempt <= 0 || (attemptTimeoutMillis != null && attemptTimeoutMillis < 0)) {
                throw new IllegalArgumentException("Model attempt lifecycle is invalid");
            }
        }
    }

    /** Response headers arrived and response-body decoding is about to begin. */
    record ResponseStarted() implements ModelEvent {}

    record RateLimited(long retryAfterMillis, int attempt) implements ModelEvent {
        public RateLimited {
            if (retryAfterMillis < 0 || attempt <= 0) {
                throw new IllegalArgumentException("Rate-limit delay and attempt are invalid");
            }
        }
    }

    record MessageComplete(String stopReason) implements ModelEvent {}
}
