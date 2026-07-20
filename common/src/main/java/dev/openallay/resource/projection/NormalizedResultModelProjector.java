package dev.openallay.resource.projection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.agent.tool.ModelToolResultView;
import java.util.Map;
import java.util.TreeSet;

/** Transitional semantic projection for legacy normalized Tool truth. */
public final class NormalizedResultModelProjector {
    private NormalizedResultModelProjector() {}

    public static ModelToolResultView project(JsonObject normalized) {
        StringBuilder output = new StringBuilder();
        String status = text(normalized, "status");
        output.append("status: ").append(status == null ? "unknown" : status).append('\n');
        if (normalized.has("outputType")) {
            output.append("type: ").append(normalized.get("outputType").getAsString()).append('\n');
        }
        if ("failure".equals(status)) {
            appendField(output, "code", normalized.get("code"), 0);
            appendField(output, "message", normalized.get("message"), 0);
        } else if (normalized.has("value")) {
            append(output, normalized.get("value"), 0, null);
        }
        String text = output.toString().stripTrailing();
        return new ModelToolResultView(text.isBlank() ? "status: unknown" : text);
    }

    private static void append(StringBuilder output, JsonElement value, int depth, String name) {
        if (value == null || value.isJsonNull() || value instanceof JsonPrimitive) {
            appendField(output, name == null ? "value" : name, value, depth);
            return;
        }
        if (value instanceof JsonArray array) {
            if (name != null) {
                indent(output, depth).append(name).append(':').append('\n');
            }
            int childDepth = name == null ? depth : depth + 1;
            for (JsonElement child : array) {
                if (child == null || child.isJsonNull() || child.isJsonPrimitive()) {
                    indent(output, childDepth).append("- ").append(scalar(child)).append('\n');
                } else {
                    indent(output, childDepth).append('-').append('\n');
                    append(output, child, childDepth + 1, null);
                }
            }
            return;
        }
        JsonObject object = value.getAsJsonObject();
        if (name != null) {
            indent(output, depth).append(name).append(':').append('\n');
        }
        int childDepth = name == null ? depth : depth + 1;
        for (String key : new TreeSet<>(object.keySet())) {
            append(output, object.get(key), childDepth, key);
        }
    }

    private static void appendField(StringBuilder output, String name, JsonElement value, int depth) {
        indent(output, depth).append(name).append(": ").append(scalar(value)).append('\n');
    }

    private static String scalar(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : primitive.toString();
    }

    private static StringBuilder indent(StringBuilder output, int depth) {
        return output.append("  ".repeat(Math.max(0, depth)));
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
