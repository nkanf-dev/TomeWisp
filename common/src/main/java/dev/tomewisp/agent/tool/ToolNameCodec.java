package dev.tomewisp.agent.tool;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ToolNameCodec {
    private final Map<String, String> encodedById;
    private final Map<String, String> idByAlias;

    public ToolNameCodec(Collection<String> toolIds) {
        Map<String, String> forward = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        for (String id : toolIds) {
            String encoded = encodeCandidate(id);
            if (encoded.length() > 64) {
                throw new IllegalArgumentException("Encoded tool name exceeds 64 characters: " + id);
            }
            String collision = aliases.putIfAbsent(encoded.toLowerCase(Locale.ROOT), id);
            if (collision != null && !collision.equals(id)) {
                throw new IllegalArgumentException(
                        "Tool name collision between " + collision + " and " + id);
            }
            collision = aliases.putIfAbsent(id.toLowerCase(Locale.ROOT), id);
            if (collision != null && !collision.equals(id)) {
                throw new IllegalArgumentException(
                        "Tool alias collision between " + collision + " and " + id);
            }
            forward.put(id, encoded);
        }
        encodedById = Map.copyOf(forward);
        idByAlias = Map.copyOf(aliases);
    }

    public String encode(String toolId) {
        String value = encodedById.get(toolId);
        if (value == null) {
            throw new IllegalArgumentException("Unknown tool id: " + toolId);
        }
        return value;
    }

    public String decode(String modelName) {
        String value = idByAlias.get(modelName.toLowerCase(Locale.ROOT));
        if (value == null) {
            throw new IllegalArgumentException("Unknown model tool name: " + modelName);
        }
        return value;
    }

    private static String encodeCandidate(String id) {
        return id.replace("/", "_slash_")
                .replace(".", "_dot_")
                .replace(":", "__");
    }
}
