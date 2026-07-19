package dev.tomewisp.settings.model;

import dev.tomewisp.model.config.ModelProtocol;
import java.time.Instant;
import java.util.Objects;

/** Credential-free result retained by the settings UI after a connection probe. */
public sealed interface ModelConnectionResult
        permits ModelConnectionResult.Success, ModelConnectionResult.Failure {
    record Success(
            String profileId,
            ModelProtocol protocol,
            String authority,
            Instant completedAt,
            long latencyMillis)
            implements ModelConnectionResult {
        public Success {
            requireText(profileId, "profileId");
            Objects.requireNonNull(protocol, "protocol");
            requireText(authority, "authority");
            Objects.requireNonNull(completedAt, "completedAt");
            if (latencyMillis < 0) {
                throw new IllegalArgumentException("latencyMillis must not be negative");
            }
        }
    }

    record Failure(String code, String message) implements ModelConnectionResult {
        public Failure {
            requireText(code, "code");
            requireText(message, "message");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
