package dev.openallay.guide.semantic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.context.RecipeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Code-owned registry and exact decoder for controlled dynamic components. */
public final class RichComponentRegistry {
    @FunctionalInterface
    public interface Decoder {
        RichComponent decode(
                String nodeId,
                RichComponentEnvelope envelope,
                SemanticReferenceIndex references);
    }

    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "schemaVersion", "type", "properties", "fallback", "narration");
    private final Map<String, Decoder> decoders;

    public RichComponentRegistry(Map<String, Decoder> decoders) {
        TreeMap<String, Decoder> copy = new TreeMap<>();
        Objects.requireNonNull(decoders, "decoders").forEach((type, decoder) -> {
            if (type == null || !type.matches("[a-z][a-z0-9_]*") || decoder == null) {
                throw new IllegalArgumentException("rich component registration is invalid");
            }
            if (copy.put(type, decoder) != null) {
                throw new IllegalArgumentException("rich component type is duplicated");
            }
        });
        this.decoders = Map.copyOf(copy);
    }

    public static RichComponentRegistry builtins() {
        return new RichComponentRegistry(BuiltinRichComponents.decoders());
    }

    Set<String> registeredTypes() {
        return decoders.keySet();
    }

    public Decode decode(String encoded, String nodeId, SemanticReferenceIndex references) {
        Objects.requireNonNull(references, "references");
        String fallback = encoded == null || encoded.isBlank()
                ? "Unsupported component" : encoded.strip();
        try {
            JsonElement parsed = JsonParser.parseString(encoded);
            if (!parsed.isJsonObject()) {
                return Decode.failure(fallback, "semantic_component_unsupported");
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement fallbackElement = object.get("fallback");
            if (fallbackElement != null && fallbackElement.isJsonPrimitive()
                    && fallbackElement.getAsJsonPrimitive().isString()
                    && !fallbackElement.getAsString().isBlank()) {
                fallback = fallbackElement.getAsString();
            }
            exact(object, ENVELOPE_KEYS);
            RichComponentEnvelope envelope = new RichComponentEnvelope(
                    integer(object, "schemaVersion"),
                    string(object, "type"),
                    object(object, "properties"),
                    string(object, "fallback"),
                    string(object, "narration"));
            Decoder decoder = decoders.get(envelope.type());
            if (decoder == null) {
                return Decode.failure(fallback, "semantic_component_unsupported");
            }
            return Decode.success(decoder.decode(nodeId, envelope, references));
        } catch (RuntimeException invalid) {
            return Decode.failure(fallback, "semantic_component_unsupported");
        }
    }

    static RichComponent.Item item(JsonObject object, SemanticReferenceIndex references) {
        exact(object, Set.of("itemId", "count", "label"));
        String itemId = string(object, "itemId");
        String origin = requireOrigin(references, SemanticReferenceKind.ITEM, itemId);
        return new RichComponent.Item(
                itemId, nonnegativeLong(object, "count"), nullableString(object, "label"), origin);
    }

    static RecipeBinding recipe(JsonObject object, SemanticReferenceIndex references) {
        String handle = RecipeSemanticHandle.encode(new RecipeReference(
                string(object, "sourceId"),
                string(object, "generation"),
                string(object, "recipeId")));
        return new RecipeBinding(
                RecipeSemanticHandle.decode(handle),
                requireOrigin(references, SemanticReferenceKind.RECIPE, handle));
    }

    static void exact(JsonObject object, Set<String> keys) {
        if (!object.keySet().equals(keys)) {
            throw new IllegalArgumentException("component object has unknown or missing keys");
        }
    }

    static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    static JsonObject object(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.getAsString();
    }

    static String nullableString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.getAsInt();
    }

    static long positiveLong(JsonObject object, String field) {
        long value = nonnegativeLong(object, field);
        if (value == 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    static long nonnegativeLong(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        long result = value.getAsLong();
        if (result < 0) throw new IllegalArgumentException(field + " must not be negative");
        return result;
    }

    static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    static List<JsonObject> objects(JsonArray values) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("array entry must be object");
            result.add(value.getAsJsonObject());
        }
        return List.copyOf(result);
    }

    static String requireOrigin(
            SemanticReferenceIndex references, SemanticReferenceKind kind, String target) {
        return references.origin(kind, target).orElseThrow(() ->
                new IllegalArgumentException("component reference is not authorized"));
    }

    record RecipeBinding(RecipeReference reference, String originInvocationId) {}

    public record Decode(RichComponent component, String fallbackText, String failureCode) {
        public Decode {
            if ((component == null) == (failureCode == null)) {
                throw new IllegalArgumentException("component decode must succeed or fail");
            }
            if (fallbackText == null || fallbackText.isBlank()) {
                throw new IllegalArgumentException("component decode fallback is required");
            }
        }

        static Decode success(RichComponent component) {
            return new Decode(
                    Objects.requireNonNull(component, "component"), component.fallbackText(), null);
        }

        static Decode failure(String fallback, String code) {
            return new Decode(null, fallback, code);
        }

        public boolean successful() {
            return component != null;
        }
    }
}
