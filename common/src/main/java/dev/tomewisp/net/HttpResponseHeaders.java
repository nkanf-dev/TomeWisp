package dev.tomewisp.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Case-insensitive, engine-neutral response headers. */
public record HttpResponseHeaders(Map<String, List<String>> values) {
    public HttpResponseHeaders {
        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        values.forEach((name, entries) -> normalized.put(
                name.toLowerCase(Locale.ROOT), List.copyOf(entries)));
        values = java.util.Collections.unmodifiableMap(normalized);
    }

    public Optional<String> firstValue(String name) {
        List<String> found = values.get(name.toLowerCase(Locale.ROOT));
        return found == null || found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }
}
