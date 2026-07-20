package dev.openallay.guide.semantic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.context.RecipeReference;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Same-request allowlist derived only from validated tool results and evidence sources. */
public final class SemanticReferenceIndex {
    private static final java.util.Set<String> ITEM_FIELDS = java.util.Set.of(
            "itemId", "item", "resolvedItems", "resolved_items", "counts", "alternatives");
    private static final java.util.Set<String> SOURCE_FIELDS = java.util.Set.of("sourceId");

    private final UUID requestId;
    private final Map<SemanticReferenceKind, Map<String, String>> origins;

    private SemanticReferenceIndex(
            UUID requestId, Map<SemanticReferenceKind, Map<String, String>> origins) {
        this.requestId = requestId;
        EnumMap<SemanticReferenceKind, Map<String, String>> copied =
                new EnumMap<>(SemanticReferenceKind.class);
        origins.forEach((kind, values) -> copied.put(kind, Map.copyOf(values)));
        this.origins = Map.copyOf(copied);
    }

    public static SemanticReferenceIndex empty(UUID requestId) {
        return new SemanticReferenceIndex(
                java.util.Objects.requireNonNull(requestId, "requestId"), Map.of());
    }

    public static SemanticReferenceIndex from(
            UUID requestId, List<GuideTimelineEntry> timeline) {
        java.util.Objects.requireNonNull(requestId, "requestId");
        EnumMap<SemanticReferenceKind, Map<String, String>> values =
                new EnumMap<>(SemanticReferenceKind.class);
        for (GuideTimelineEntry entry : List.copyOf(timeline)) {
            if (!(entry instanceof GuideTimelineEntry.Tool tool)) {
                continue;
            }
            GuideToolActivity activity = tool.activity();
            String origin = activity.invocationId();
            activity.uiReference().primaryResources().forEach(path ->
                    collectPath(path.toString(), origin, values));
            for (GuideSource source : activity.sources()) {
                put(values, SemanticReferenceKind.SOURCE, source.evidence().sourceId(), origin);
                put(values, SemanticReferenceKind.EVIDENCE, source.evidence().sourceId(), origin);
            }
            JsonObject normalized = activity.normalized();
            if (normalized != null && "success".equals(string(normalized, "status"))) {
                collect(normalized.get("value"), null, origin, values);
            }
        }
        return new SemanticReferenceIndex(requestId, values);
    }

    public UUID requestId() {
        return requestId;
    }

    public Optional<String> origin(SemanticReferenceKind kind, String target) {
        Map<String, String> values = origins.get(kind);
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(target));
    }

    private static void collect(
            JsonElement element,
            String field,
            String origin,
            Map<SemanticReferenceKind, Map<String, String>> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            recipe(object).ifPresent(reference -> put(
                    values,
                    SemanticReferenceKind.RECIPE,
                    RecipeSemanticHandle.encode(reference),
                    origin));
            resourceRecipe(object).ifPresent(reference -> put(
                    values,
                    SemanticReferenceKind.RECIPE,
                    RecipeSemanticHandle.encode(reference),
                    origin));
            if ("sources".equals(field)) {
                String sourceId = string(object, "id");
                if (!sourceId.isBlank()) {
                    put(values, SemanticReferenceKind.SOURCE, sourceId, origin);
                }
            }
            if ("counts".equals(field)) {
                object.keySet().forEach(item -> putResource(
                        values, SemanticReferenceKind.ITEM, item, origin));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collect(entry.getValue(), entry.getKey(), origin, values);
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement value : array) {
                collect(value, field, origin, values);
            }
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && ITEM_FIELDS.contains(field)) {
            putResource(values, SemanticReferenceKind.ITEM, element.getAsString(), origin);
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && ("path".equals(field) || "target".equals(field))) {
            collectPath(element.getAsString(), origin, values);
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && SOURCE_FIELDS.contains(field)) {
            put(values, SemanticReferenceKind.SOURCE, element.getAsString(), origin);
        }
    }

    private static Optional<RecipeReference> recipe(JsonObject object) {
        if (!string(object, "sourceId").isBlank()
                && !string(object, "generation").isBlank()
                && !string(object, "recipeId").isBlank()) {
            try {
                return Optional.of(new RecipeReference(
                        string(object, "sourceId"),
                        string(object, "generation"),
                        string(object, "recipeId")));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<RecipeReference> resourceRecipe(JsonObject object) {
        if (!string(object, "source").isBlank()
                && !string(object, "source_generation").isBlank()
                && !string(object, "id").isBlank()) {
            try {
                return Optional.of(new RecipeReference(
                        string(object, "source"),
                        string(object, "source_generation"),
                        string(object, "id")));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static void collectPath(
            String encoded,
            String origin,
            Map<SemanticReferenceKind, Map<String, String>> values) {
        try {
            dev.openallay.resource.vfs.ResourcePath path =
                    dev.openallay.resource.vfs.ResourcePath.parse(encoded);
            if (path.segments().size() < 3) {
                return;
            }
            String id = path.segments().get(1) + ":" + path.segments().get(2);
            SemanticReferenceKind kind = switch (path.mount()) {
                case "item" -> SemanticReferenceKind.ITEM;
                case "block" -> SemanticReferenceKind.BLOCK;
                case "fluid" -> SemanticReferenceKind.FLUID;
                case "entity" -> SemanticReferenceKind.ENTITY;
                case "biome" -> SemanticReferenceKind.BIOME;
                default -> null;
            };
            if (kind != null) {
                putResource(values, kind, id, origin);
            }
        } catch (IllegalArgumentException ignored) {
            // Only canonical VFS paths contribute same-request semantic authority.
        }
    }

    private static void putResource(
            Map<SemanticReferenceKind, Map<String, String>> values,
            SemanticReferenceKind kind,
            String target,
            String origin) {
        if (SemanticReferenceValidator.isResourceId(target)) {
            put(values, kind, target, origin);
        }
    }

    private static void put(
            Map<SemanticReferenceKind, Map<String, String>> values,
            SemanticReferenceKind kind,
            String target,
            String origin) {
        values.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).putIfAbsent(target, origin);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }
}
