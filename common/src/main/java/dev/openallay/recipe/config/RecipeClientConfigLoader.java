package dev.openallay.recipe.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.tool.ToolResult;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class RecipeClientConfigLoader {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "visibility", "preferredViewer", "disabledSources");

    public ToolResult<RecipeClientConfig> load(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return new ToolResult.Success<>(RecipeClientConfig.defaults());
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return load(reader);
        } catch (IOException failure) {
            return new ToolResult.Failure<>(
                    "invalid_recipe_config", "Unable to read recipe configuration");
        }
    }

    public ToolResult<RecipeClientConfig> load(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        try {
            JsonObject object = object(JsonParser.parseReader(reader), "Recipe configuration");
            exactFields(object, ROOT_FIELDS, "recipe configuration");
            int schema = integer(object, "schemaVersion");
            RecipeVisibilityPolicy visibility = enumValue(
                    RecipeVisibilityPolicy.class, string(object, "visibility"));
            String preferred = string(object, "preferredViewer");
            JsonArray disabled = array(object, "disabledSources");
            LinkedHashSet<String> disabledSources = new LinkedHashSet<>();
            for (JsonElement encoded : disabled) {
                if (!encoded.isJsonPrimitive() || !encoded.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("disabledSources entries must be strings");
                }
                String sourceId = encoded.getAsString();
                if (!disabledSources.add(sourceId)) {
                    throw new IllegalArgumentException("disabledSources must not contain duplicates");
                }
            }
            return new ToolResult.Success<>(new RecipeClientConfig(
                    schema, visibility, preferred, disabledSources));
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_recipe_config",
                    failure.getMessage() == null
                            ? "Invalid recipe configuration"
                            : failure.getMessage());
        }
    }

    private static void exactFields(JsonObject object, Set<String> fields, String name) {
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(name + " fields must be exactly " + fields);
        }
    }

    private static JsonElement required(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return object.get(field);
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank text");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
