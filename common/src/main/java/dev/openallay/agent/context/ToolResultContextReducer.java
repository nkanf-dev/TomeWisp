package dev.openallay.agent.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Replaces bulk historical tool outputs with evidence-bearing stable memory. */
public final class ToolResultContextReducer {
    private static final Set<String> CONCLUSION_KEYS = Set.of(
            "available",
            "capability",
            "complete",
            "completeness",
            "conclusive",
            "craftable",
            "found",
            "matched",
            "maximumCrafts",
            "missingCount",
            "requestedCrafts",
            "state",
            "unlockState",
            "visibility");
    private static final Set<String> STABLE_ID_KEYS = Set.of(
            "referenceId",
            "documentId",
            "entryId",
            "multiblockId",
            "resourceId");

    public ContextProjection reduce(List<ModelMessage> messages, int protectedFromIndex) {
        messages = List.copyOf(messages);
        List<ContextStructure.Unit> units = ContextStructure.units(messages);
        ContextStructure.requireBoundary(units, protectedFromIndex, messages.size());
        ArrayList<ModelMessage> reduced = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ModelMessage message = messages.get(messageIndex);
            if (messageIndex >= protectedFromIndex) {
                reduced.add(message);
                continue;
            }
            ArrayList<ModelContent> content = new ArrayList<>(message.content().size());
            for (ModelContent item : message.content()) {
                if (item instanceof ModelContent.ToolResult result) {
                    ReducedToolResult projection = reduceResult(result.value(), result.error());
                    content.add(new ModelContent.ToolResult(
                            result.toolUseId(), projection.value(), projection.error()));
                    changed = true;
                } else {
                    content.add(item);
                }
            }
            reduced.add(changed && content.stream().anyMatch(ModelContent.ToolResult.class::isInstance)
                    ? new ModelMessage(message.role(), content)
                    : message);
        }
        return ContextProjection.unestimated(
                reduced,
                changed
                        ? ContextProjection.Kind.TOOL_RESULTS_REDUCED
                        : ContextProjection.Kind.ORIGINAL);
    }

    public ReducedToolResult reduceResult(JsonElement value, boolean originalError) {
        if (value == null || !value.isJsonObject()) {
            return malformed();
        }
        JsonObject source = value.getAsJsonObject();
        String status = text(source, "status");
        if (status == null) {
            return malformed();
        }
        if (status.equals("failure")) {
            JsonObject failure = new JsonObject();
            failure.addProperty("status", "failure");
            addText(source, failure, "code");
            addText(source, failure, "message");
            if (!failure.has("code")) {
                failure.addProperty("code", "historical_tool_failure");
            }
            if (!failure.has("message")) {
                failure.addProperty("message", "Historical tool call failed");
            }
            return new ReducedToolResult(failure, true);
        }
        if (!status.equals("success")) {
            return malformed();
        }

        JsonObject result = new JsonObject();
        result.addProperty("status", "success");
        addText(source, result, "outputType");
        Collector collector = new Collector();
        if (source.has("value")) {
            collect(source.get("value"), collector, false);
        }
        if (!collector.conclusions.isEmpty()) {
            JsonObject conclusions = new JsonObject();
            collector.conclusions.forEach(conclusions::add);
            result.add("conclusions", conclusions);
        }
        if (!collector.references.isEmpty()) {
            JsonArray references = new JsonArray();
            collector.references.values().forEach(references::add);
            result.add("references", references);
        }
        if (!collector.stableIds.isEmpty()) {
            JsonArray stableIds = new JsonArray();
            collector.stableIds.values().forEach(stableIds::add);
            result.add("stableIds", stableIds);
        }
        if (!collector.evidence.isEmpty()) {
            JsonArray evidence = new JsonArray();
            collector.evidence.values().forEach(evidence::add);
            result.add("evidence", evidence);
        }
        return new ReducedToolResult(result, originalError);
    }

    private static void collect(JsonElement element, Collector collector, boolean insideReference) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collect(item, collector, insideReference);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        boolean referenceObject = insideReference || looksLikeReference(object);
        if (referenceObject && looksLikeReference(object)) {
            collector.references.putIfAbsent(object.toString(), object.deepCopy());
        }
        for (String key : new java.util.TreeSet<>(object.keySet())) {
            JsonElement child = object.get(key);
            if (key.equals("evidence") && child.isJsonArray()) {
                for (JsonElement evidence : child.getAsJsonArray()) {
                    if (evidence.isJsonObject()) {
                        collector.evidence.putIfAbsent(evidence.toString(), evidence.deepCopy());
                    }
                }
                continue;
            }
            if (key.equals("reference") && child.isJsonObject()) {
                JsonObject reference = child.getAsJsonObject();
                collector.references.putIfAbsent(reference.toString(), reference.deepCopy());
                collect(child, collector, true);
                continue;
            }
            if (!insideReference && CONCLUSION_KEYS.contains(key) && child.isJsonPrimitive()) {
                collector.conclusions.putIfAbsent(key, child.deepCopy());
            }
            if (!insideReference && STABLE_ID_KEYS.contains(key)
                    && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                JsonObject stable = new JsonObject();
                stable.addProperty("kind", key);
                stable.addProperty("value", child.getAsString());
                collector.stableIds.putIfAbsent(key + "\u0000" + child.getAsString(), stable);
            }
            collect(child, collector, referenceObject);
        }
    }

    private static boolean looksLikeReference(JsonObject object) {
        if (!object.has("sourceId")) {
            return false;
        }
        return object.has("recipeId")
                || object.has("referenceId")
                || object.has("documentId")
                || object.has("entryId")
                || object.has("resourceId");
    }

    private static ReducedToolResult malformed() {
        JsonObject failure = new JsonObject();
        failure.addProperty("status", "failure");
        failure.addProperty("code", "context_result_malformed");
        failure.addProperty("message", "Historical tool result was not normalized");
        return new ReducedToolResult(failure, true);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static void addText(JsonObject source, JsonObject target, String key) {
        String value = text(source, key);
        if (value != null) {
            target.addProperty(key, value);
        }
    }

    private static final class Collector {
        private final Map<String, JsonElement> conclusions = new TreeMap<>();
        private final Map<String, JsonElement> references = new LinkedHashMap<>();
        private final Map<String, JsonElement> stableIds = new LinkedHashMap<>();
        private final Map<String, JsonElement> evidence = new LinkedHashMap<>();
    }
}
