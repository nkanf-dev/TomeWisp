package dev.openallay.tool.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.tool.ToolResult;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict schema-1 codec for one logical Tool and its owned source envelopes. */
public final class ToolFamilyConfigCodec {
    private static final Set<String> ROOT_FIELDS =
            Set.of("schemaVersion", "toolId", "enabled", "sources");
    private static final Set<String> SOURCE_FIELDS =
            Set.of("sourceId", "sourceKind", "displayName", "enabled", "config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ToolSourceKindRegistry registry;

    public ToolFamilyConfigCodec(ToolSourceKindRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ToolResult<ToolFamilyConfig> decode(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw invalid("Tool-family configuration must be an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            exactFields(root, ROOT_FIELDS, "Tool-family configuration");
            int schema = integer(root, "schemaVersion");
            ToolFamilyId family = ToolFamilyId.fromSerializedId(string(root, "toolId"));
            boolean enabled = bool(root, "enabled");
            JsonElement sourcesElement = required(root, "sources");
            if (!sourcesElement.isJsonArray()) {
                throw invalid("sources must be an array");
            }
            List<ToolSourceDefinition> sources = new ArrayList<>();
            for (JsonElement encoded : sourcesElement.getAsJsonArray()) {
                if (!encoded.isJsonObject()) {
                    throw invalid("sources entries must be objects");
                }
                JsonObject source = encoded.getAsJsonObject();
                exactFields(source, SOURCE_FIELDS, "Tool source");
                String sourceId = string(source, "sourceId");
                String sourceKind = string(source, "sourceKind");
                ToolSourceKind kind = registry.find(sourceKind).orElseThrow(() -> new ToolConfigException(
                        "unknown_source_kind", "Unknown source kind " + sourceKind));
                if (kind.owner() != family) {
                    throw new ToolConfigException(
                            "source_owner_mismatch",
                            "Source kind " + sourceKind + " belongs to " + kind.owner().serializedId());
                }
                JsonElement configElement = required(source, "config");
                if (!configElement.isJsonObject()) {
                    throw invalid("source config must be an object");
                }
                JsonObject validatedConfig = kind.validateConfig(configElement.getAsJsonObject());
                sources.add(new ToolSourceDefinition(
                        sourceId,
                        sourceKind,
                        string(source, "displayName"),
                        bool(source, "enabled"),
                        validatedConfig,
                        ToolSourceDefinition.lifecycleForId(sourceId)));
            }
            ToolFamilyConfig decoded = new ToolFamilyConfig(schema, family, enabled, sources);
            ToolResult<ToolFamilyConfig> validation = registry.validate(decoded);
            if (validation instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
                throw new ToolConfigException(failure.code(), failure.message());
            }
            return new ToolResult.Success<>(decoded);
        } catch (ToolConfigException failure) {
            return new ToolResult.Failure<>("invalid_tool_family_config", failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_tool_family_config",
                    failure.getMessage() == null
                            ? "Invalid Tool-family configuration"
                            : failure.getMessage());
        }
    }

    public String encode(ToolFamilyConfig config) {
        Objects.requireNonNull(config, "config");
        ToolResult<ToolFamilyConfig> validation = registry.validate(config);
        if (validation instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            throw new ToolConfigException(failure.code(), failure.message());
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("toolId", config.toolId().serializedId());
        root.addProperty("enabled", config.enabled());
        JsonArray sources = new JsonArray();
        for (ToolSourceDefinition source : config.sources()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("sourceId", source.sourceId());
            encoded.addProperty("sourceKind", source.sourceKind());
            encoded.addProperty("displayName", source.displayName());
            encoded.addProperty("enabled", source.enabled());
            ToolSourceKind kind = registry.find(source.sourceKind()).orElseThrow();
            encoded.add("config", kind.validateConfig(source.config()));
            sources.add(encoded);
        }
        root.add("sources", sources);
        return GSON.toJson(root) + "\n";
    }

    private static void exactFields(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            throw invalid(label + " fields must be exactly " + expected);
        }
    }

    private static JsonElement required(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw invalid(field + " is required");
        }
        return object.get(field);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid(field + " must be an integer");
        }
    }

    private static ToolConfigException invalid(String message) {
        return new ToolConfigException("invalid_tool_family_config", message);
    }
}
