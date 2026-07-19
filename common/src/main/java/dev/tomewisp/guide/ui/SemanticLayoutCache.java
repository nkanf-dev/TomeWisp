package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.semantic.SemanticDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Targeted semantic measurement cache keyed by stable row and presentation identity. */
public final class SemanticLayoutCache {
    public record Stats(long hits, long misses, int entries) {}
    private static final AtomicLong GLOBAL_HITS = new AtomicLong();
    private static final AtomicLong GLOBAL_MISSES = new AtomicLong();
    private record Key(
            String rowId, int contentHash, int width, String locale,
            String fontIdentity) {}

    private final Map<Key, SemanticLayout> values = new LinkedHashMap<>();
    private long hits;
    private long misses;

    public SemanticLayout get(
            String rowId,
            SemanticDocument document,
            int width,
            String locale,
            String fontIdentity,
            SemanticLayoutEngine.Measurer measurer) {
        Key key = new Key(
                require(rowId), document.hashCode(), width, require(locale),
                require(fontIdentity));
        SemanticLayout cached = values.get(key);
        if (cached != null) {
            hits++;
            GLOBAL_HITS.incrementAndGet();
            return cached;
        }
        misses++;
        GLOBAL_MISSES.incrementAndGet();
        SemanticLayout created = new SemanticLayoutEngine().layout(document, width, measurer);
        values.put(key, created);
        return created;
    }

    public void invalidateRow(String rowId) {
        values.keySet().removeIf(key -> key.rowId().equals(rowId));
    }

    public void clear() {
        values.clear();
    }

    public Stats stats() {
        return new Stats(hits, misses, values.size());
    }

    public static Stats globalStats() {
        return new Stats(GLOBAL_HITS.get(), GLOBAL_MISSES.get(), 0);
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("cache identity is required");
        return value;
    }
}
