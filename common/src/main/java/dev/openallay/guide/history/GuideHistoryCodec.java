package dev.openallay.guide.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.agent.context.ContextCheckpointCodec;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.agent.tool.ToolUiSummary;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessageCodec;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.semantic.SemanticDocumentCodec;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict codecs for player-visible durable projections and derived checkpoints. */
public final class GuideHistoryCodec {
    private final ContextCheckpointCodec checkpoints = new ContextCheckpointCodec();
    private final SemanticDocumentCodec semanticDocuments = new SemanticDocumentCodec();
    private static final Set<String> ASSISTANT_FIELDS =
            Set.of("type", "ordinal", "text", "semantic", "streaming", "sources");
    private static final Set<String> LEGACY_TOOL_FIELDS = Set.of(
            "type", "ordinal", "invocationId", "index", "toolId", "status",
            "presentationMessages", "sources");
    private static final Set<String> TOOL_FIELDS = Set.of(
            "type", "ordinal", "invocationId", "index", "toolId", "status",
            "uiReference", "diagnostics", "presentationMessages", "sources");
    private static final Set<String> UI_REFERENCE_FIELDS = Set.of(
            "resultPath", "primaryResources", "presentationKind", "continuationAvailable", "summary");
    private static final Set<String> UI_SUMMARY_FIELDS = Set.of(
            "operation", "succeeded", "failed", "resourceKinds");
    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of(
            "normalizedBytes", "modelCharacters", "generationId", "projectedAt");
    private static final Set<String> SOURCE_FIELDS = Set.of("toolId", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "authority", "completeness", "capturedAt", "sourceId", "provenance",
            "gameVersion", "loader", "details");
    private static final Set<String> SERVER_SELECTION_FIELDS = Set.of("kind");
    private static final Set<String> CLIENT_SELECTION_FIELDS = Set.of("kind", "profileId");

    public String encodeModelSelection(GuideModelSelection selection) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("kind", selection.kind().name());
        if (selection.kind() == GuideModelSelection.Kind.CLIENT) {
            encoded.addProperty("profileId", selection.profileId());
        }
        return encoded.toString();
    }

    public GuideModelSelection decodeModelSelection(String json) {
        JsonObject encoded = object(JsonParser.parseString(json), "model selection");
        GuideModelSelection.Kind kind = enumValue(
                GuideModelSelection.Kind.class,
                string(encoded, "kind"),
                "model selection kind");
        return switch (kind) {
            case CLIENT -> {
                requireFields(encoded, CLIENT_SELECTION_FIELDS, "client model selection");
                yield GuideModelSelection.client(string(encoded, "profileId"));
            }
            case SERVER -> {
                requireFields(encoded, SERVER_SELECTION_FIELDS, "server model selection");
                yield GuideModelSelection.server();
            }
        };
    }

    public String encodeCheckpoint(ContextCheckpoint checkpoint) {
        return checkpoints.encode(checkpoint);
    }

    public ContextCheckpoint decodeCheckpoint(String json) {
        return checkpoints.decode(json);
    }

    public String encodeTimeline(List<GuideTimelineEntry> timeline) {
        JsonArray encoded = new JsonArray();
        for (GuideTimelineEntry entry : timeline) {
            encoded.add(encodeEntryObject(entry));
        }
        return encoded.toString();
    }

    public String encodeEntry(GuideTimelineEntry entry) {
        return encodeEntryObject(entry).toString();
    }

    public GuideTimelineEntry decodeEntry(String json) {
        return decodeEntryObject(object(JsonParser.parseString(json), "timeline entry"));
    }

    public List<GuideTimelineEntry> decodeTimeline(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("durable timeline must be an array");
        }
        List<GuideTimelineEntry> decoded = new ArrayList<>();
        for (JsonElement element : parsed.getAsJsonArray()) {
            decoded.add(decodeEntryObject(object(element, "timeline entry")));
        }
        for (int index = 0; index < decoded.size(); index++) {
            if (decoded.get(index).ordinal() != index) {
                throw new IllegalArgumentException("durable timeline ordinals must be contiguous");
            }
        }
        return List.copyOf(decoded);
    }

    private JsonObject encodeEntryObject(GuideTimelineEntry entry) {
        return switch (entry) {
            case GuideTimelineEntry.Assistant assistant -> encodeAssistant(assistant);
            case GuideTimelineEntry.Tool tool -> encodeTool(tool);
        };
    }

    private GuideTimelineEntry decodeEntryObject(JsonObject object) {
        String type = string(object, "type");
        return switch (type) {
            case "assistant" -> decodeAssistant(object);
            case "tool" -> decodeTool(object);
            default -> throw new IllegalArgumentException(
                    "unknown durable timeline entry type " + type);
        };
    }

    private JsonObject encodeAssistant(GuideTimelineEntry.Assistant assistant) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "assistant");
        object.addProperty("ordinal", assistant.ordinal());
        object.addProperty("text", assistant.text());
        object.add("semantic", semanticDocuments.encodeObject(assistant.semantic()));
        object.addProperty("streaming", assistant.streaming());
        object.add("sources", encodeSourcesArray(assistant.sources()));
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
        object.add("uiReference", encodeUiReference(activity.uiReference()));
        object.add("diagnostics", encodeDiagnostics(activity.diagnostics()));
        object.add("presentationMessages", GuideToolMessageCodec.encode(
                activity.presentationMessages()));
        object.add("sources", encodeSourcesArray(activity.sources()));
        return object;
    }

    private GuideTimelineEntry.Assistant decodeAssistant(JsonObject object) {
        requireFields(object, ASSISTANT_FIELDS, "assistant timeline entry");
        return new GuideTimelineEntry.Assistant(
                integer(object, "ordinal"),
                string(object, "text"),
                semanticDocuments.decodeObject(object(object.get("semantic"), "semantic document")),
                bool(object, "streaming"),
                decodeSourcesArray(array(object, "sources")));
    }

    private static GuideTimelineEntry.Tool decodeTool(JsonObject object) {
        boolean legacy = object.keySet().equals(LEGACY_TOOL_FIELDS);
        if (!legacy) {
            requireFields(object, TOOL_FIELDS, "tool timeline entry");
        }
        GuideToolActivity activity = new GuideToolActivity(
                string(object, "invocationId"),
                integer(object, "index"),
                string(object, "toolId"),
                enumValue(GuideToolStatus.class, string(object, "status"), "tool status"),
                null,
                legacy
                        ? ToolUiReference.none()
                        : decodeUiReference(object(object.get("uiReference"), "tool UI reference")),
                legacy
                        ? ToolResultDiagnostics.none()
                        : decodeDiagnostics(object(object.get("diagnostics"), "tool diagnostics")),
                GuideToolMessageCodec.decode(object.get("presentationMessages")),
                decodeSourcesArray(array(object, "sources")));
        return new GuideTimelineEntry.Tool(integer(object, "ordinal"), activity);
    }

    private static JsonObject encodeUiReference(ToolUiReference reference) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty(
                "resultPath",
                reference.resultPath() == null ? "" : reference.resultPath().toString());
        JsonArray resources = new JsonArray();
        reference.primaryResources().forEach(path -> resources.add(path.toString()));
        encoded.add("primaryResources", resources);
        encoded.addProperty("presentationKind", reference.presentationKind().name());
        encoded.addProperty("continuationAvailable", reference.continuationAvailable());
        JsonObject summary = new JsonObject();
        summary.addProperty("operation", reference.summary().operation());
        summary.addProperty("succeeded", reference.summary().succeeded());
        summary.addProperty("failed", reference.summary().failed());
        JsonArray kinds = new JsonArray();
        reference.summary().resourceKinds().forEach(kinds::add);
        summary.add("resourceKinds", kinds);
        encoded.add("summary", summary);
        return encoded;
    }

    private static ToolUiReference decodeUiReference(JsonObject encoded) {
        requireFields(encoded, UI_REFERENCE_FIELDS, "tool UI reference");
        String result = string(encoded, "resultPath");
        List<ResourcePath> resources = new ArrayList<>();
        for (JsonElement element : array(encoded, "primaryResources")) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("tool UI resource path must be text");
            }
            resources.add(ResourcePath.parse(element.getAsString()));
        }
        return new ToolUiReference(
                result.isBlank() ? null : ResourcePath.parse(result),
                resources,
                enumValue(
                        ResourcePresentation.Kind.class,
                        string(encoded, "presentationKind"),
                        "presentation kind"),
                bool(encoded, "continuationAvailable"),
                decodeUiSummary(object(encoded.get("summary"), "tool UI summary")));
    }

    private static ToolUiSummary decodeUiSummary(JsonObject encoded) {
        requireFields(encoded, UI_SUMMARY_FIELDS, "tool UI summary");
        List<String> kinds = new ArrayList<>();
        for (JsonElement element : array(encoded, "resourceKinds")) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("tool UI resource kind must be text");
            }
            kinds.add(element.getAsString());
        }
        return new ToolUiSummary(
                string(encoded, "operation"),
                integer(encoded, "succeeded"),
                integer(encoded, "failed"),
                kinds);
    }

    private static JsonObject encodeDiagnostics(ToolResultDiagnostics diagnostics) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("normalizedBytes", diagnostics.normalizedBytes());
        encoded.addProperty("modelCharacters", diagnostics.modelCharacters());
        encoded.addProperty("generationId", diagnostics.generationId());
        encoded.addProperty("projectedAt", diagnostics.projectedAt().toString());
        return encoded;
    }

    private static ToolResultDiagnostics decodeDiagnostics(JsonObject encoded) {
        requireFields(encoded, DIAGNOSTIC_FIELDS, "tool diagnostics");
        return new ToolResultDiagnostics(
                integer(encoded, "normalizedBytes"),
                integer(encoded, "modelCharacters"),
                string(encoded, "generationId"),
                Instant.parse(string(encoded, "projectedAt")));
    }

    public String encodeSources(List<GuideSource> sources) {
        return encodeSourcesArray(sources).toString();
    }

    public List<GuideSource> decodeSources(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("durable sources must be an array");
        }
        return decodeSourcesArray(parsed.getAsJsonArray());
    }

    private static JsonArray encodeSourcesArray(List<GuideSource> sources) {
        JsonArray encoded = new JsonArray();
        for (GuideSource source : sources) {
            JsonObject object = new JsonObject();
            object.addProperty("toolId", source.toolId());
            object.add("evidence", encodeEvidence(source.evidence()));
            encoded.add(object);
        }
        return encoded;
    }

    private static List<GuideSource> decodeSourcesArray(JsonArray sources) {
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
