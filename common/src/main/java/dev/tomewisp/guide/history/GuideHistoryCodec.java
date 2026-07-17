package dev.tomewisp.guide.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict schema-v1 codecs for player-visible durable projections. */
public final class GuideHistoryCodec {
    private static final Set<String> ASSISTANT_FIELDS =
            Set.of("type", "ordinal", "text", "streaming", "sources");
    private static final Set<String> TOOL_FIELDS = Set.of(
            "type", "ordinal", "invocationId", "index", "toolId", "status",
            "presentationLines", "sources");
    private static final Set<String> SOURCE_FIELDS = Set.of("toolId", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "authority", "completeness", "capturedAt", "sourceId", "provenance",
            "gameVersion", "loader", "details");

    public String encodeTimeline(List<GuideTimelineEntry> timeline) {
        JsonArray encoded = new JsonArray();
        for (GuideTimelineEntry entry : timeline) {
            encoded.add(switch (entry) {
                case GuideTimelineEntry.Assistant assistant -> encodeAssistant(assistant);
                case GuideTimelineEntry.Tool tool -> encodeTool(tool);
            });
        }
        return encoded.toString();
    }

    public List<GuideTimelineEntry> decodeTimeline(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("durable timeline must be an array");
        }
        List<GuideTimelineEntry> decoded = new ArrayList<>();
        for (JsonElement element : parsed.getAsJsonArray()) {
            JsonObject object = object(element, "timeline entry");
            String type = string(object, "type");
            decoded.add(switch (type) {
                case "assistant" -> decodeAssistant(object);
                case "tool" -> decodeTool(object);
                default -> throw new IllegalArgumentException(
                        "unknown durable timeline entry type " + type);
            });
        }
        for (int index = 0; index < decoded.size(); index++) {
            if (decoded.get(index).ordinal() != index) {
                throw new IllegalArgumentException("durable timeline ordinals must be contiguous");
            }
        }
        return List.copyOf(decoded);
    }

    private static JsonObject encodeAssistant(GuideTimelineEntry.Assistant assistant) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "assistant");
        object.addProperty("ordinal", assistant.ordinal());
        object.addProperty("text", assistant.text());
        object.addProperty("streaming", assistant.streaming());
        object.add("sources", encodeSources(assistant.sources()));
        return object;
    }

    private static JsonObject encodeTool(GuideTimelineEntry.Tool tool) {
        GuideToolActivity activity = tool.activity();
        JsonObject object = new JsonObject();
        object.addProperty("type", "tool");
        object.addProperty("ordinal", tool.ordinal());
        object.addProperty("invocationId", activity.invocationId());
        object.addProperty("index", activity.index());
        object.addProperty("toolId", activity.toolId());
        object.addProperty("status", activity.status().name());
        JsonArray lines = new JsonArray();
        activity.presentationLines().forEach(lines::add);
        object.add("presentationLines", lines);
        object.add("sources", encodeSources(activity.sources()));
        return object;
    }

    private static GuideTimelineEntry.Assistant decodeAssistant(JsonObject object) {
        requireFields(object, ASSISTANT_FIELDS, "assistant timeline entry");
        return new GuideTimelineEntry.Assistant(
                integer(object, "ordinal"),
                string(object, "text"),
                bool(object, "streaming"),
                decodeSources(array(object, "sources")));
    }

    private static GuideTimelineEntry.Tool decodeTool(JsonObject object) {
        requireFields(object, TOOL_FIELDS, "tool timeline entry");
        List<String> lines = new ArrayList<>();
        for (JsonElement line : array(object, "presentationLines")) {
            if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("durable presentation line must be text");
            }
            lines.add(line.getAsString());
        }
        GuideToolActivity activity = new GuideToolActivity(
                string(object, "invocationId"),
                integer(object, "index"),
                string(object, "toolId"),
                enumValue(GuideToolStatus.class, string(object, "status"), "tool status"),
                null,
                lines,
                decodeSources(array(object, "sources")));
        return new GuideTimelineEntry.Tool(integer(object, "ordinal"), activity);
    }

    private static JsonArray encodeSources(List<GuideSource> sources) {
        JsonArray encoded = new JsonArray();
        for (GuideSource source : sources) {
            JsonObject object = new JsonObject();
            object.addProperty("toolId", source.toolId());
            object.add("evidence", encodeEvidence(source.evidence()));
            encoded.add(object);
        }
        return encoded;
    }

    private static List<GuideSource> decodeSources(JsonArray sources) {
        List<GuideSource> decoded = new ArrayList<>();
        for (JsonElement element : sources) {
            JsonObject object = object(element, "durable source");
            requireFields(object, SOURCE_FIELDS, "durable source");
            decoded.add(new GuideSource(
                    string(object, "toolId"),
                    decodeEvidence(object(object.get("evidence"), "durable evidence"))));
        }
        return List.copyOf(decoded);
    }

    private static JsonObject encodeEvidence(EvidenceMetadata evidence) {
        JsonObject object = new JsonObject();
        object.addProperty("authority", evidence.authority().name());
        object.addProperty("completeness", evidence.completeness().name());
        object.addProperty("capturedAt", evidence.capturedAt().toString());
        object.addProperty("sourceId", evidence.sourceId());
        object.addProperty("provenance", evidence.provenance());
        object.addProperty("gameVersion", evidence.gameVersion());
        object.addProperty("loader", evidence.loader());
        JsonObject details = new JsonObject();
        evidence.details().forEach(details::addProperty);
        object.add("details", details);
        return object;
    }

    private static EvidenceMetadata decodeEvidence(JsonObject object) {
        requireFields(object, EVIDENCE_FIELDS, "durable evidence");
        JsonObject encodedDetails = object(object.get("details"), "evidence details");
        Map<String, String> details = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : encodedDetails.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("evidence detail must be text");
            }
            details.put(entry.getKey(), value.getAsString());
        }
        return new EvidenceMetadata(
                enumValue(DataAuthority.class, string(object, "authority"), "authority"),
                enumValue(DataCompleteness.class, string(object, "completeness"), "completeness"),
                Instant.parse(string(object, "capturedAt")),
                string(object, "sourceId"),
                string(object, "provenance"),
                string(object, "gameVersion"),
                string(object, "loader"),
                details);
    }

    private static void requireFields(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            Set<String> missing = new java.util.TreeSet<>(expected);
            missing.removeAll(object.keySet());
            Set<String> extra = new java.util.TreeSet<>(object.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException(
                    label + " schema mismatch; missing=" + missing + ", extra=" + extra);
        }
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown durable " + label + " " + value, failure);
        }
    }
}
