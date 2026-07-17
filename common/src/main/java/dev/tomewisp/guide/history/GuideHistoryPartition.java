package dev.tomewisp.guide.history;

import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideSessionSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record GuideHistoryPartition(
        int schemaVersion,
        GuideHistoryScope scope,
        String selectedSession,
        GuideModelMode modelMode,
        List<GuideSessionSnapshot> sessions,
        Instant updatedAt) {
    public static final int SCHEMA_VERSION = 1;

    public GuideHistoryPartition {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported durable history schema " + schemaVersion);
        }
        Objects.requireNonNull(scope, "scope");
        if (selectedSession == null || selectedSession.isBlank()) {
            throw new IllegalArgumentException("selectedSession must not be blank");
        }
        Objects.requireNonNull(modelMode, "modelMode");
        sessions = List.copyOf(sessions);
        if (sessions.stream().noneMatch(session -> session.sessionId().equals(selectedSession))) {
            throw new IllegalArgumentException("selectedSession does not exist in durable history");
        }
        Set<UUID> allRequests = new HashSet<>();
        for (GuideSessionSnapshot session : sessions) {
            Set<UUID> sessionRequests = new HashSet<>();
            session.requests().forEach(request -> {
                if (!request.sessionId().equals(session.sessionId())) {
                    throw new IllegalArgumentException("durable request belongs to another session");
                }
                if (!sessionRequests.add(request.requestId()) || !allRequests.add(request.requestId())) {
                    throw new IllegalArgumentException("durable request identity is duplicated");
                }
            });
            for (GuideMessage message : session.messages()) {
                if (!sessionRequests.contains(message.requestId())) {
                    throw new IllegalArgumentException("durable message has no same-session request");
                }
            }
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
