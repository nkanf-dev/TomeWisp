package dev.openallay.agent.tool.result;

import com.google.gson.JsonObject;
import dev.openallay.model.ModelToolResultText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Request-scoped exact Tool results behind opaque, non-path references. */
public final class ToolResultResourceStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String retain(Owner owner, JsonObject exact) {
        String ref = "result_" + UUID.randomUUID().toString().replace("-", "");
        JsonObject copy = exact.deepCopy();
        byte[] bytes = copy.toString().getBytes(StandardCharsets.UTF_8);
        entries.put(ref, new Entry(
                owner, copy, ModelToolResultText.lines(copy), bytes.length, sha256(bytes)));
        return ref;
    }

    public Optional<Descriptor> describe(Owner owner, String ref) {
        Entry entry = owned(owner, ref);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor(ref));
    }

    public Optional<Page> read(Owner owner, String ref, int offset, String query, int byteBudget) {
        Entry entry = owned(owner, ref);
        if (entry == null || offset < 0 || byteBudget < 256) return Optional.empty();
        String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        List<String> selected = needle.isEmpty()
                ? entry.lines()
                : entry.lines().stream()
                        .filter(line -> line.toLowerCase(java.util.Locale.ROOT).contains(needle))
                        .toList();
        if (offset > selected.size()) return Optional.empty();
        List<String> page = new java.util.ArrayList<>();
        int bytes = 0;
        int index = offset;
        while (index < selected.size()) {
            String line = selected.get(index);
            int next = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (!page.isEmpty() && bytes + next > byteBudget) break;
            if (page.isEmpty() && next > byteBudget) {
                page.add(utf8Prefix(line, byteBudget - 1));
                index++;
                break;
            }
            page.add(line);
            bytes += next;
            index++;
        }
        return Optional.of(new Page(
                entry.descriptor(ref), List.copyOf(page), index < selected.size() ? index : null,
                selected.size()));
    }

    public void release(Owner owner) {
        entries.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
    }

    public record Owner(String actorId, String correlationId) {
        public Owner {
            if (actorId == null || actorId.isBlank()
                    || correlationId == null || correlationId.isBlank()) {
                throw new IllegalArgumentException("result owner identity is required");
            }
        }
    }

    public record Descriptor(String resultRef, int totalBytes, int lineCount, String sha256) {}

    public record Page(Descriptor resource, List<String> lines, Integer nextOffset, int matchCount) {
        public Page { lines = List.copyOf(lines); }
    }

    private Entry owned(Owner owner, String ref) {
        if (owner == null || ref == null || ref.isBlank()) return null;
        Entry entry = entries.get(ref);
        return entry != null && entry.owner().equals(owner) ? entry : null;
    }

    static String utf8Prefix(String value, int maxBytes) {
        if (maxBytes <= 0) return "";
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Entry(Owner owner, JsonObject exact, List<String> lines, int bytes, String sha256) {
        private Descriptor descriptor(String ref) {
            return new Descriptor(ref, bytes, lines.size(), sha256);
        }
    }
}
