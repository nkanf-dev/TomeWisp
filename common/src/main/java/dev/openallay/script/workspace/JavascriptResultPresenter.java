package dev.openallay.script.workspace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Compact model projection; canonical JSON remains in the workspace. */
public final class JavascriptResultPresenter {
    private static final int PREVIEW_ROWS = 6;
    private static final int PREVIEW_TEXT = 1_600;

    public Presentation present(String handle, JsonElement value) {
        String type = type(value);
        long cardinality = cardinality(value);
        List<String> fields = fields(value);
        boolean complete = !value.isJsonArray()
                || value.getAsJsonArray().size() <= PREVIEW_ROWS;
        int omitted = complete || !value.isJsonArray()
                ? 0
                : value.getAsJsonArray().size() - PREVIEW_ROWS;
        JsonElement preview = preview(value);
        String rendered = render(handle, type, cardinality, fields, preview, omitted);
        if (rendered.length() > PREVIEW_TEXT) {
            rendered = rendered.substring(0, PREVIEW_TEXT)
                    + "\npreview: shortened; reopen the handle and project fewer fields";
            complete = false;
        }
        return new Presentation(handle, type, cardinality, fields, rendered, complete, omitted);
    }

    private static JsonElement preview(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() <= PREVIEW_ROWS) {
            return value.deepCopy();
        }
        com.google.gson.JsonArray result = new com.google.gson.JsonArray();
        for (int index = 0; index < PREVIEW_ROWS; index++) {
            result.add(value.getAsJsonArray().get(index).deepCopy());
        }
        return result;
    }

    private static String render(
            String handle,
            String type,
            long cardinality,
            List<String> fields,
            JsonElement preview,
            int omitted) {
        StringBuilder result = new StringBuilder();
        result.append("result: ").append(handle).append('\n');
        result.append("type: ").append(type).append('\n');
        result.append("cardinality: ").append(cardinality).append('\n');
        if (!fields.isEmpty()) {
            result.append("fields: ").append(String.join(", ", fields)).append('\n');
        }
        result.append("preview: ").append(preview);
        if (omitted > 0) {
            result.append('\n')
                    .append("omitted: ")
                    .append(omitted)
                    .append(" row(s); use workspace.open(\"")
                    .append(handle)
                    .append("\") in a later script to filter, aggregate, or project them");
        }
        return result.toString();
    }

    private static String type(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return "boolean";
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return "number";
        }
        return "string";
    }

    private static long cardinality(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return 0;
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().size();
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject().size();
        }
        return 1;
    }

    private static List<String> fields(JsonElement value) {
        TreeSet<String> fields = new TreeSet<>();
        if (value != null && value.isJsonObject()) {
            fields.addAll(value.getAsJsonObject().keySet());
        } else if (value != null && value.isJsonArray()) {
            for (JsonElement row : value.getAsJsonArray()) {
                if (row.isJsonObject()) {
                    fields.addAll(row.getAsJsonObject().keySet());
                }
                if (fields.size() >= 32) {
                    break;
                }
            }
        }
        return new ArrayList<>(fields);
    }

    public record Presentation(
            String handle,
            String type,
            long cardinality,
            List<String> fields,
            String modelText,
            boolean complete,
            int omittedRows) {
        public Presentation {
            fields = List.copyOf(fields);
        }
    }
}

