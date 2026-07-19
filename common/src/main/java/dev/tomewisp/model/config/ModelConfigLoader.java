package dev.tomewisp.model.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.tool.ToolResult;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ModelConfigLoader {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "enabled",
            "protocol",
            "baseUrl",
            "model",
            "apiKeyEnv",
            "apiKey",
            "contextWindowTokens",
            "maxOutputTokens",
            "connectTimeoutSeconds",
            "requestTimeoutSeconds");

    public ToolResult<ModelConfig> load(Path path, Map<String, String> environment) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return new ToolResult.Failure<>(
                    "model_not_configured", "Model configuration does not exist: " + path);
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return load(reader, environment);
        } catch (IOException exception) {
            return new ToolResult.Failure<>(
                    "invalid_model_config", "Unable to read model configuration: " + exception.getMessage());
        }
    }

    public ToolResult<ModelConfig> load(Reader reader, Map<String, String> environment) {
        Objects.requireNonNull(reader, "reader");
        environment = Map.copyOf(environment);
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Model configuration must be a JSON object");
            }
            JsonObject object = root.getAsJsonObject();
            for (String field : object.keySet()) {
                if (!ALLOWED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Unknown model configuration field: " + field);
                }
            }
            boolean enabled = bool(environment, "TOMEWISP_MODEL_ENABLED", object, "enabled", true);
            ModelProtocol protocol = ModelProtocol.valueOf(string(
                            environment,
                            "TOMEWISP_MODEL_PROTOCOL",
                            object,
                            "protocol",
                            "anthropic_messages")
                    .toUpperCase(Locale.ROOT));
            String baseUrl = string(environment, "TOMEWISP_MODEL_BASE_URL", object, "baseUrl", null);
            String model = string(environment, "TOMEWISP_MODEL", object, "model", null);
            String keyEnvName = string(Map.of(), "", object, "apiKeyEnv", "TOMEWISP_API_KEY");
            String apiKey = firstNonBlank(
                    environment.get(keyEnvName),
                    environment.get("TOMEWISP_API_KEY"),
                    optionalString(object, "apiKey"));
            int maxOutputTokens = integer(
                    environment, "TOMEWISP_MAX_OUTPUT_TOKENS", object, "maxOutputTokens", 8192);
            int contextWindowTokens = requiredInteger(
                    environment,
                    "TOMEWISP_CONTEXT_WINDOW_TOKENS",
                    object,
                    "contextWindowTokens");
            int connectSeconds = integer(
                    environment, "TOMEWISP_CONNECT_TIMEOUT_SECONDS", object, "connectTimeoutSeconds", 30);
            int requestSeconds = integer(
                    environment, "TOMEWISP_REQUEST_TIMEOUT_SECONDS", object, "requestTimeoutSeconds", 300);
            return new ToolResult.Success<>(new ModelConfig(
                    enabled,
                    protocol,
                    java.net.URI.create(require(baseUrl, "baseUrl")),
                    require(model, "model"),
                    SecretValue.of(require(apiKey, "API key")),
                    contextWindowTokens,
                    maxOutputTokens,
                    Duration.ofSeconds(connectSeconds),
                    Duration.ofSeconds(requestSeconds)));
        } catch (RuntimeException exception) {
            return new ToolResult.Failure<>(
                    "invalid_model_config",
                    exception.getMessage() == null ? "Invalid model configuration" : exception.getMessage());
        }
    }

    private static String string(
            Map<String, String> environment,
            String environmentName,
            JsonObject object,
            String field,
            String fallback) {
        String fromEnvironment = environmentName.isEmpty() ? null : environment.get(environmentName);
        return firstNonBlank(fromEnvironment, optionalString(object, field), fallback);
    }

    private static String optionalString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        if (!object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return object.get(field).getAsString();
    }

    private static boolean bool(
            Map<String, String> environment,
            String environmentName,
            JsonObject object,
            String field,
            boolean fallback) {
        String fromEnvironment = environment.get(environmentName);
        if (fromEnvironment != null) {
            if (!fromEnvironment.equalsIgnoreCase("true")
                    && !fromEnvironment.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException(environmentName + " must be true or false");
            }
            return Boolean.parseBoolean(fromEnvironment);
        }
        if (!object.has(field)) {
            return fallback;
        }
        if (!object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return object.get(field).getAsBoolean();
    }

    private static int integer(
            Map<String, String> environment,
            String environmentName,
            JsonObject object,
            String field,
            int fallback) {
        String value = environment.get(environmentName);
        if (value == null && object.has(field)) {
            value = object.get(field).getAsString();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static int requiredInteger(
            Map<String, String> environment,
            String environmentName,
            JsonObject object,
            String field) {
        String value = environment.get(environmentName);
        if (value == null && object.has(field)) {
            value = object.get(field).getAsString();
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required unless trusted model metadata resolves it");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
