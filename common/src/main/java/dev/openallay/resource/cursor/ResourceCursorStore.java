package dev.openallay.resource.cursor;

import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourceView;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live-only cursor registry with owner, request, connection and captured-view binding. */
public final class ResourceCursorStore implements AutoCloseable {
    private static final int TOKEN_BYTES = 24;

    private final Map<String, ResourceCursor> cursors = new ConcurrentHashMap<>();
    private final SecureRandom random;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourceCursorStore() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    ResourceCursorStore(SecureRandom random, Clock clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String issue(ResourceCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        ensureOpen();
        if (cursor.expiresAt() != null && !cursor.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("cursor must expire in the future");
        }
        while (true) {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (cursors.putIfAbsent(token, cursor) == null) {
                return token;
            }
        }
    }

    public ResourceCursor resolve(
            String token,
            UUID actorId,
            String sessionId,
            String requestId,
            ResourceView view,
            String queryDigest) {
        ensureOpen();
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(view, "view");
        ResourceCursor cursor = cursors.get(requireToken(token));
        if (cursor == null) {
            throw invalidCursor();
        }
        Instant now = clock.instant();
        if (cursor.expiresAt() != null && !cursor.expiresAt().isAfter(now)) {
            cursors.remove(token, cursor);
            throw new ResourceOperationException("cursor_expired", "Resource cursor has expired");
        }
        var scope = view.scope();
        boolean matches = cursor.actorId().equals(actorId)
                && cursor.sessionId().equals(sessionId)
                && cursor.requestId().equals(requestId)
                && cursor.connectionGeneration() == scope.connectionGeneration()
                && stableGenerations(cursor.viewGenerations()).equals(stableGenerations(view.generationIds()))
                && cursor.queryDigest().equals(queryDigest)
                && scope.actorId().equals(actorId)
                && scope.sessionId().equals(sessionId)
                && scope.requestId().equals(requestId);
        if (!matches) {
            // Deliberately indistinguishable from a forged token or another owner's token.
            throw invalidCursor();
        }
        return cursor;
    }

    private static Map<String, String> stableGenerations(Map<String, String> generations) {
        java.util.TreeMap<String, String> stable = new java.util.TreeMap<>(generations);
        // /result is the one request-owned dynamic mount. Publishing a derived result must not
        // invalidate a cursor into an earlier immutable result record.
        stable.remove("result");
        return Map.copyOf(stable);
    }

    /** Resolves a cursor-only continuation while retaining the original query digest internally. */
    public ResourceCursor resolve(
            String token,
            UUID actorId,
            String sessionId,
            String requestId,
            ResourceView view) {
        ensureOpen();
        ResourceCursor cursor = cursors.get(requireToken(token));
        if (cursor == null) throw invalidCursor();
        return resolve(token, actorId, sessionId, requestId, view, cursor.queryDigest());
    }

    public void releaseRequest(UUID actorId, String sessionId, String requestId) {
        cursors.entrySet().removeIf(entry -> {
            ResourceCursor cursor = entry.getValue();
            return cursor.actorId().equals(actorId)
                    && cursor.sessionId().equals(sessionId)
                    && cursor.requestId().equals(requestId);
        });
    }

    public void releaseSession(UUID actorId, String sessionId) {
        cursors.entrySet().removeIf(entry -> entry.getValue().actorId().equals(actorId)
                && entry.getValue().sessionId().equals(sessionId));
    }

    public void releaseConnection(long connectionGeneration) {
        cursors.entrySet().removeIf(entry ->
                entry.getValue().connectionGeneration() == connectionGeneration);
    }

    public int size() {
        return cursors.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cursors.clear();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ResourceOperationException("cursor_expired", "Resource cursor store is closed");
        }
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw invalidCursor();
        }
        return token;
    }

    private static ResourceOperationException invalidCursor() {
        return new ResourceOperationException("invalid_cursor", "Resource cursor is invalid for this request");
    }
}
