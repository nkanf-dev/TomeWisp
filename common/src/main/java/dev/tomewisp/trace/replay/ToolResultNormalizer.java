package dev.tomewisp.trace.replay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.context.EvidenceBearing;
import java.util.Objects;
import java.util.TreeSet;

public final class ToolResultNormalizer {
    private final Gson gson;

    public ToolResultNormalizer(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public JsonObject normalize(ToolResult<?> result, Class<?> outputType) {
        JsonObject normalized = new JsonObject();
        if (result instanceof ToolResult.Success<?> success) {
            if (EvidenceBearing.class.isAssignableFrom(outputType)) {
                if (!(success.value() instanceof EvidenceBearing grounded)
                        || grounded.evidence() == null
                        || grounded.evidence().isEmpty()) {
                    throw new IllegalArgumentException("Grounded tool output has no evidence");
                }
            }
            normalized.addProperty("status", "success");
            normalized.addProperty("outputType", outputType.getName());
            normalized.add("value", canonicalize(gson.toJsonTree(success.value())));
        } else if (result instanceof ToolResult.Failure<?> failure) {
            normalized.addProperty("status", "failure");
            normalized.addProperty("code", failure.code());
            normalized.addProperty("message", failure.message());
        } else {
            throw new IllegalArgumentException("Unknown ToolResult implementation: " + result);
        }
        return canonicalize(normalized).getAsJsonObject();
    }

    public JsonElement canonicalize(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return value == null ? null : value.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                result.add(canonicalize(element));
            }
            return result;
        }

        JsonObject result = new JsonObject();
        JsonObject object = value.getAsJsonObject();
        for (String key : new TreeSet<>(object.keySet())) {
            result.add(key, canonicalize(object.get(key)));
        }
        return result;
    }
}
