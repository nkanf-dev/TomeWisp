package dev.tomewisp.agent.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class ContextCheckpointCodec {
    private static final Set<String> FIELDS = Set.of(
            "checkpointId", "sourceFromIndex", "sourceToIndexExclusive", "sourceHash",
            "modelIdentifier", "promptVersion", "schemaVersion", "createdAt", "status",
            "summary", "failureCode", "failureMessage", "estimatedProjectionTokens");

    public String encode(ContextCheckpoint checkpoint) {
        JsonObject object = new JsonObject();
        object.addProperty("checkpointId", checkpoint.checkpointId().toString());
        object.addProperty("sourceFromIndex", checkpoint.sourceFromIndex());
        object.addProperty("sourceToIndexExclusive", checkpoint.sourceToIndexExclusive());
        object.addProperty("sourceHash", checkpoint.sourceHash());
        object.addProperty("modelIdentifier", checkpoint.modelIdentifier());
        object.addProperty("promptVersion", checkpoint.promptVersion());
        object.addProperty("schemaVersion", checkpoint.schemaVersion());
        object.addProperty("createdAt", checkpoint.createdAt().toString());
        object.addProperty("status", checkpoint.status().name());
        nullable(object, "summary", checkpoint.summary());
        nullable(object, "failureCode", checkpoint.failureCode());
        nullable(object, "failureMessage", checkpoint.failureMessage());
        object.addProperty("estimatedProjectionTokens", checkpoint.estimatedProjectionTokens());
        return object.toString();
    }

    public ContextCheckpoint decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("checkpoint schema mismatch");
        }
        JsonObject object = parsed.getAsJsonObject();
        return new ContextCheckpoint(
                UUID.fromString(text(object, "checkpointId")),
                integer(object, "sourceFromIndex"),
                integer(object, "sourceToIndexExclusive"),
                text(object, "sourceHash"),
                text(object, "modelIdentifier"),
                integer(object, "promptVersion"),
                integer(object, "schemaVersion"),
                Instant.parse(text(object, "createdAt")),
                ContextCheckpoint.Status.valueOf(text(object, "status")),
                nullableText(object, "summary"),
                nullableText(object, "failureCode"),
                nullableText(object, "failureMessage"),
                integer(object, "estimatedProjectionTokens"));
    }

    private static void nullable(JsonObject object, String key, String value) {
        if (value == null) object.add(key, com.google.gson.JsonNull.INSTANCE);
        else object.addProperty(key, value);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        return value.getAsString();
    }

    private static String nullableText(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : text(object, key);
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.get(key).getAsBigDecimal().intValueExact();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
    }
}
