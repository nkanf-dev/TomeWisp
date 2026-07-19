package dev.openallay.client.gui.settings;

import dev.openallay.settings.tool.ToolSettingsView;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.tool.config.ToolFamilyConfig;
import dev.openallay.tool.config.ToolFamilyId;
import dev.openallay.tool.config.ToolSourceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Player-friendly Tools master-detail projection; technical IDs are debug-only text. */
public record ToolSettingsProjection(List<Family> families, boolean debugMode) {
    public ToolSettingsProjection {
        families = List.copyOf(families);
    }

    public static ToolSettingsProjection from(ToolSettingsView view, boolean debugMode) {
        Objects.requireNonNull(view, "view");
        return new ToolSettingsProjection(
                view.families().stream().map(family -> Family.from(family, debugMode)).toList(),
                debugMode);
    }

    public Optional<Family> find(ToolFamilyId id) {
        return families.stream().filter(family -> family.id() == id).findFirst();
    }

    public ToolFamilyConfig toggleTool(ToolFamilyId id) {
        Family selected = find(id).orElseThrow();
        return selected.toConfig(!selected.enabled());
    }

    public ToolFamilyConfig toggleSource(ToolFamilyId id, String sourceId) {
        Family selected = find(id).orElseThrow();
        List<ToolSourceDefinition> sources = new ArrayList<>();
        boolean found = false;
        for (Source source : selected.sources()) {
            if (source.id().equals(sourceId)) {
                found = true;
                sources.add(source.toDefinition(!source.enabled()));
            } else {
                sources.add(source.toDefinition(source.enabled()));
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Unknown Tool source " + sourceId);
        }
        return new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, selected.enabled(), sources);
    }

    public record Family(
            ToolFamilyId id,
            String titleKey,
            String descriptionKey,
            boolean enabled,
            boolean available,
            List<ToolCard> tools,
            List<Source> sources,
            ToolSettingsView.RecipeDetail recipes) {
        public Family {
            tools = List.copyOf(tools);
            sources = List.copyOf(sources);
        }

        static Family from(ToolSettingsView.Family family, boolean debugMode) {
            return new Family(
                    family.id(),
                    family.titleKey(),
                    family.descriptionKey(),
                    family.enabled(),
                    family.available(),
                    family.members().stream()
                            .map(member -> ToolCard.from(member, family.descriptionKey(), debugMode))
                            .toList(),
                    family.sources().stream().map(Source::from).toList(),
                    family.recipeDetail());
        }

        ToolFamilyConfig toConfig(boolean replacementEnabled) {
            return new ToolFamilyConfig(
                    ToolFamilyConfig.SCHEMA_VERSION,
                    id,
                    replacementEnabled,
                    sources.stream()
                            .map(source -> source.toDefinition(source.enabled()))
                            .toList());
        }
    }

    /** One friendly callable card. Protocol identities and schemas exist only in Debug. */
    public record ToolCard(
            String selectionId,
            String titleKey,
            String descriptionKey,
            String scopeKey,
            boolean available,
            boolean enabled,
            boolean readOnly,
            List<Parameter> parameters,
            List<ReturnField> returns,
            Optional<Debug> debug) {
        public ToolCard {
            parameters = List.copyOf(parameters);
            returns = List.copyOf(returns);
            debug = Objects.requireNonNull(debug, "debug");
        }

        static ToolCard from(
                ToolSettingsView.Member member, String scopeKey, boolean debugMode) {
            JsonObject input = member.inputSchema();
            JsonObject output = member.outputSchema();
            return new ToolCard(
                    member.id(),
                    member.titleKey(),
                    member.descriptionKey(),
                    scopeKey,
                    member.available(),
                    member.enabled(),
                    member.access().equals("READ_ONLY"),
                    fields(input, true).stream().map(Parameter::from).toList(),
                    fields(output, false).stream().map(ReturnField::from).toList(),
                    debugMode ? Optional.of(new Debug(
                            member.id(),
                            member.modelAlias(),
                            pretty(input),
                            pretty(output),
                            member.providerId(),
                            member.executionKey(),
                            member.access(),
                            member.requiredContext().stream().map(Enum::name).sorted().toList(),
                            member.schemaDiagnostic())) : Optional.empty());
        }
    }

    public record Parameter(
            String name, String label, String type, boolean required, String description) {
        static Parameter from(Field field) {
            return new Parameter(
                    field.name(), friendly(field.name()), field.type(), field.required(),
                    field.description());
        }
    }

    public record ReturnField(String name, String label, String type, String description) {
        static ReturnField from(Field field) {
            return new ReturnField(
                    field.name(), friendly(field.name()), field.type(), field.description());
        }
    }

    public record Debug(
            String toolId,
            String modelAlias,
            String inputSchema,
            String outputSchema,
            String providerId,
            String executionKey,
            String access,
            List<String> requiredContext,
            String diagnostic) {
        public Debug {
            requiredContext = List.copyOf(requiredContext);
        }
    }

    private record Field(
            String name, String type, boolean required, String description) {}

    private static List<Field> fields(JsonObject schema, boolean input) {
        if (schema == null || !schema.has("properties")
                || !schema.get("properties").isJsonObject()) {
            return List.of();
        }
        java.util.Set<String> required = new java.util.HashSet<>();
        if (input && schema.has("required") && schema.get("required").isJsonArray()) {
            schema.getAsJsonArray("required").forEach(value -> required.add(value.getAsString()));
        }
        List<Field> fields = new ArrayList<>();
        for (var entry : schema.getAsJsonObject("properties").entrySet()) {
            JsonObject property = entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject() : new JsonObject();
            fields.add(new Field(
                    entry.getKey(),
                    type(property),
                    required.contains(entry.getKey()),
                    property.has("description")
                            ? property.get("description").getAsString() : ""));
        }
        return List.copyOf(fields);
    }

    private static String type(JsonObject schema) {
        String value = schema.has("type") ? schema.get("type").getAsString() : "value";
        if (value.equals("array") && schema.has("items") && schema.get("items").isJsonObject()) {
            return "array<" + type(schema.getAsJsonObject("items")) + ">";
        }
        if (schema.has("enum") && schema.get("enum").isJsonArray()) {
            return schema.getAsJsonArray("enum").asList().stream()
                    .map(JsonElement::getAsString)
                    .collect(java.util.stream.Collectors.joining(" | "));
        }
        return value;
    }

    private static String friendly(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ').trim();
        if (spaced.isEmpty()) return name;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String pretty(JsonObject schema) {
        if (schema == null) return "";
        // Schemas contain contract metadata only, never runtime argument values. Defensive
        // structural redaction prevents future annotations from turning Debug into a secret sink
        // while keeping the rendered document valid JSON.
        return new GsonBuilder().setPrettyPrinting().create().toJson(redact(schema));
    }

    private static JsonElement redact(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return value == null ? com.google.gson.JsonNull.INSTANCE : value.deepCopy();
        }
        if (value.isJsonArray()) {
            com.google.gson.JsonArray result = new com.google.gson.JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(redact(element)));
            return result;
        }
        JsonObject result = new JsonObject();
        value.getAsJsonObject().entrySet().forEach(entry -> result.add(
                entry.getKey(), sensitive(entry.getKey())
                        ? new com.google.gson.JsonPrimitive("[redacted]")
                        : redact(entry.getValue())));
        return result;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return normalized.equals("authorization")
                || normalized.equals("apikey")
                || normalized.equals("accesstoken")
                || normalized.equals("password");
    }

    public record Source(
            String id,
            String kind,
            String displayName,
            boolean enabled,
            boolean available,
            ToolSourceDefinition.Lifecycle lifecycle,
            boolean editable,
            boolean deletable,
            com.google.gson.JsonObject config) {
        public Source {
            config = config.deepCopy();
        }

        static Source from(ToolSettingsView.Source source) {
            return new Source(
                    source.id(),
                    source.kind(),
                    source.displayName(),
                    source.enabled(),
                    source.available(),
                    source.lifecycle(),
                    source.editable(),
                    source.deletable(),
                    source.config());
        }

        @Override
        public com.google.gson.JsonObject config() {
            return config.deepCopy();
        }

        ToolSourceDefinition toDefinition(boolean replacementEnabled) {
            return new ToolSourceDefinition(
                    id, kind, displayName, replacementEnabled, config, lifecycle);
        }
    }
}
