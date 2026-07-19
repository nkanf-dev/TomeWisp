package dev.openallay.bridge;

import dev.openallay.model.CancellationSignal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CorrelationRegistry {
    public record Entry(UUID actorId, CancellationSignal cancellation) {}

    private final Map<UUID, Entry> entries = new HashMap<>();

    public synchronized boolean register(UUID actorId, UUID correlationId, CancellationSignal cancellation) {
        return entries.putIfAbsent(correlationId, new Entry(actorId, cancellation)) == null;
    }

    public synchronized Optional<Entry> find(UUID actorId, UUID correlationId) {
        Entry entry = entries.get(correlationId);
        return entry != null && entry.actorId().equals(actorId) ? Optional.of(entry) : Optional.empty();
    }

    public synchronized boolean complete(UUID actorId, UUID correlationId) {
        Entry entry = entries.get(correlationId);
        if (entry == null || !entry.actorId().equals(actorId)) {
            return false;
        }
        entries.remove(correlationId);
        return true;
    }

    public synchronized boolean cancel(UUID actorId, UUID correlationId) {
        Optional<Entry> entry = find(actorId, correlationId);
        if (entry.isEmpty()) {
            return false;
        }
        entries.remove(correlationId);
        entry.orElseThrow().cancellation().cancel();
        return true;
    }

    public synchronized int cancelActor(UUID actorId) {
        var owned = entries.entrySet().stream()
                .filter(entry -> entry.getValue().actorId().equals(actorId))
                .map(Map.Entry::getKey)
                .toList();
        owned.forEach(id -> cancel(actorId, id));
        return owned.size();
    }
}
