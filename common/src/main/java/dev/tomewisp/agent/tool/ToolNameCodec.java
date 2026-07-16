package dev.tomewisp.agent.tool;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class ToolNameCodec {
    private final Map<String, String> encodedById;
    private final Map<String, String> idByEncoded;

    public ToolNameCodec(Collection<String> toolIds) {
        Map<String, String> forward = new HashMap<>();
        Map<String, String> reverse = new HashMap<>();
        for (String id : toolIds) {
            String encoded = encodeCandidate(id);
            if (encoded.length() > 64) {
                throw new IllegalArgumentException("Encoded tool name exceeds 64 characters: " + id);
            }
            String collision = reverse.putIfAbsent(encoded, id);
            if (collision != null && !collision.equals(id)) {
                throw new IllegalArgumentException(
                        "Tool name collision between " + collision + " and " + id);
            }
            forward.put(id, encoded);
        }
        encodedById = Map.copyOf(forward);
        idByEncoded = Map.copyOf(reverse);
    }

    public String encode(String toolId) {
        String value = encodedById.get(toolId);
        if (value == null) {
            throw new IllegalArgumentException("Unknown tool id: " + toolId);
        }
        return value;
    }

    public String decode(String modelName) {
        String value = idByEncoded.get(modelName);
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
