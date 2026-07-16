package dev.tomewisp.trace.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.model.AgentTrace;
import dev.tomewisp.trace.model.AssistantMessageStep;
import dev.tomewisp.trace.model.ExpectationMatch;
import dev.tomewisp.trace.model.ToolCallStep;
import dev.tomewisp.trace.model.TraceExpectation;
import dev.tomewisp.trace.model.TraceStep;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TraceParser {
    private static final Set<String> TRACE_FIELDS =
            Set.of("schemaVersion", "id", "userMessage", "requiredContext", "steps");
    private static final Set<String> TOOL_STEP_FIELDS =
            Set.of("type", "tool", "arguments", "expect");
    private static final Set<String> MESSAGE_STEP_FIELDS = Set.of("type", "content");
    private static final Set<String> EXPECTATION_FIELDS =
            Set.of("status", "match", "value", "outputType");

    public ToolResult<AgentTrace> parse(Reader source) {
        try {
            JsonReader reader = new JsonReader(source);
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement root = readElement(reader, "$");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid("Unexpected data after trace document");
            }
            return new ToolResult.Success<>(parseTrace(requireObject(root, "$")));
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            String message = exception.getMessage();
            return new ToolResult.Failure<>(
                    "invalid_trace", message == null || message.isBlank() ? "Invalid trace" : message);
        }
    }

    private AgentTrace parseTrace(JsonObject object) {
        requireFields(object, "$", TRACE_FIELDS, TRACE_FIELDS);
        int schemaVersion = requireInt(object, "schemaVersion", "$", 1);
        String id = requireString(object, "id", "$");
        String userMessage = requireString(object, "userMessage", "$");

        EnumSet<ContextCapability> capabilities = EnumSet.noneOf(ContextCapability.class);
        JsonArray requiredContext = requireArray(object.get("requiredContext"), "$.requiredContext");
        for (int index = 0; index < requiredContext.size(); index++) {
            String value = requireString(requiredContext.get(index), "$.requiredContext[" + index + "]");
            ContextCapability capability = switch (value) {
                case "registries" -> ContextCapability.REGISTRIES;
                case "recipes" -> ContextCapability.RECIPES;
                case "player" -> ContextCapability.PLAYER;
                default -> throw invalid("Unknown context capability: " + value);
            };
            if (!capabilities.add(capability)) {
                throw invalid("Duplicate context capability: " + value);
            }
        }

        JsonArray stepArray = requireArray(object.get("steps"), "$.steps");
        List<TraceStep> steps = new ArrayList<>();
        for (int index = 0; index < stepArray.size(); index++) {
            steps.add(parseStep(requireObject(stepArray.get(index), "$.steps[" + index + "]"), index));
        }
        return new AgentTrace(schemaVersion, id, userMessage, capabilities, steps);
    }

    private TraceStep parseStep(JsonObject object, int index) {
        String path = "$.steps[" + index + "]";
        String type = requireString(object, "type", path);
        return switch (type) {
            case "tool_call" -> {
                requireFields(object, path, TOOL_STEP_FIELDS, TOOL_STEP_FIELDS);
                yield new ToolCallStep(
                        requireString(object, "tool", path),
                        requireObject(object.get("arguments"), path + ".arguments"),
                        parseExpectation(requireObject(object.get("expect"), path + ".expect"), path));
            }
            case "assistant_message" -> {
                requireFields(object, path, MESSAGE_STEP_FIELDS, MESSAGE_STEP_FIELDS);
                yield new AssistantMessageStep(requireString(object, "content", path));
            }
            default -> throw invalid("Unknown step type at " + path + ": " + type);
        };
    }

    private TraceExpectation parseExpectation(JsonObject object, String stepPath) {
        String path = stepPath + ".expect";
        requireFields(object, path, EXPECTATION_FIELDS, Set.of("status", "match"));
        String status = requireString(object, "status", path);
        String matchName = requireString(object, "match", path);
        ExpectationMatch match;
        try {
            match = ExpectationMatch.valueOf(matchName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("Unknown expectation match at " + path + ": " + matchName);
        }

        JsonElement value = object.has("value") ? object.get("value") : null;
        String outputType = object.has("outputType")
                ? requireString(object, "outputType", path)
                : null;
        if (match == ExpectationMatch.SCHEMA && object.has("value")) {
            throw invalid("Schema expectation must not declare value at " + path);
        }
        if (match != ExpectationMatch.SCHEMA && object.has("outputType")) {
            throw invalid("Only schema expectation may declare outputType at " + path);
        }
        return new TraceExpectation(status, match, value, outputType);
    }

    private static JsonElement readElement(JsonReader reader, String path) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader, path);
            case BEGIN_ARRAY -> readArray(reader, path);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw invalid("Unexpected JSON token at " + path + ": " + reader.peek());
        };
    }

    private static JsonObject readObject(JsonReader reader, String path) throws IOException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw invalid("Duplicate field at " + path + ": " + name);
            }
            object.add(name, readElement(reader, path + "." + name));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(JsonReader reader, String path) throws IOException {
        JsonArray array = new JsonArray();
        reader.beginArray();
        int index = 0;
        while (reader.hasNext()) {
            array.add(readElement(reader, path + "[" + index + "]"));
            index++;
        }
        reader.endArray();
        return array;
    }

    private static void requireFields(
            JsonObject object, String path, Set<String> allowed, Set<String> required) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid("Unknown field at " + path + ": " + field);
            }
        }
        for (String field : required) {
            if (!object.has(field)) {
                throw invalid("Missing field at " + path + ": " + field);
            }
        }
    }

    private static JsonObject requireObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw invalid("Expected object at " + path);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement element, String path) {
        if (element == null || !element.isJsonArray()) {
            throw invalid("Expected array at " + path);
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field, String path) {
        if (!object.has(field)) {
            throw invalid("Missing field at " + path + ": " + field);
        }
        return requireString(object.get(field), path + "." + field);
    }

    private static String requireString(JsonElement element, String path) {
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw invalid("Expected string at " + path);
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject object, String field, String path, int expected) {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid("Expected integer at " + path + "." + field);
        }
        String number = element.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) {
            throw invalid("Expected integer at " + path + "." + field);
        }
        int value;
        try {
            value = Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            throw invalid("Expected integer at " + path + "." + field);
        }
        if (value != expected) {
            throw invalid("Unsupported trace schema: " + value);
        }
        return value;
    }

    private static JsonParseException invalid(String message) {
        return new JsonParseException(message);
    }
}
