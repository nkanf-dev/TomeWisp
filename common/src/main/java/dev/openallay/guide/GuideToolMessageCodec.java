package dev.openallay.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict JSON codec for the closed Tool presentation vocabulary. */
public final class GuideToolMessageCodec {
    private static final Set<String> FIELDS = Set.of("key", "arguments");

    private GuideToolMessageCodec() {}

    public static JsonArray encode(List<GuideToolMessage> messages) {
        JsonArray encoded = new JsonArray();
        for (GuideToolMessage message : messages) {
            JsonObject object = new JsonObject();
            object.addProperty("key", message.key().name());
            JsonArray arguments = new JsonArray();
            message.arguments().forEach(arguments::add);
            object.add("arguments", arguments);
            encoded.add(object);
        }
        return encoded;
    }

    public static List<GuideToolMessage> decode(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Tool presentation must be an array");
        }
        List<GuideToolMessage> messages = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject() || !element.getAsJsonObject().keySet().equals(FIELDS)) {
                throw new IllegalArgumentException("Tool presentation message schema mismatch");
            }
            JsonObject object = element.getAsJsonObject();
            if (!object.get("key").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("key").isString()
                    || !object.get("arguments").isJsonArray()) {
                throw new IllegalArgumentException("Tool presentation message types are invalid");
            }
            GuideToolMessage.Key key;
            try {
                key = GuideToolMessage.Key.valueOf(object.get("key").getAsString());
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown Tool presentation message key", unknown);
            }
            List<String> arguments = new ArrayList<>();
            for (JsonElement argument : object.getAsJsonArray("arguments")) {
                if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Tool presentation arguments must be strings");
                }
                arguments.add(argument.getAsString());
            }
            messages.add(new GuideToolMessage(key, arguments));
        }
        return List.copyOf(messages);
    }
}
