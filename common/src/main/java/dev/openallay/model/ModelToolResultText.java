package dev.openallay.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic compact text projection for model-facing Tool results. */
public final class ModelToolResultText {
    public static final int MAX_PROVIDER_BYTES = 8 * 1024;

    private ModelToolResultText() {}

    public static String render(JsonElement value) {
        return fitUtf8(String.join("\n", lines(value)) + "\n", MAX_PROVIDER_BYTES);
    }

    public static List<String> lines(JsonElement value) {
        List<String> output = new ArrayList<>();
        flatten(value, "", output);
        return List.copyOf(output);
    }

    public static String fitUtf8(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static void flatten(JsonElement value, String path, List<String> lines) {
        if (value == null || value.isJsonNull()) {
            lines.add(label(path) + " = null");
            return;
        }
        if (value.isJsonPrimitive()) {
            lines.add(label(path) + " = " + scalar(value));
            return;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            lines.add(label(path) + ".count = " + array.size());
            for (int index = 0; index < array.size(); index++) {
                flatten(array.get(index), path + "[" + index + "]", lines);
            }
            return;
        }
        JsonObject object = value.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if ("outputType".equals(entry.getKey())) continue;
            String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            flatten(entry.getValue(), child, lines);
        }
    }

    private static String label(String path) {
        return path.isEmpty() ? "value" : path;
    }

    private static String scalar(JsonElement value) {
        if (value.getAsJsonPrimitive().isString()) {
            return value.getAsString().replace("\\", "\\\\")
                    .replace("\r", "\\r").replace("\n", "\\n");
        }
        return value.toString();
    }
}
