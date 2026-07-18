package dev.tomewisp.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelProfilesConfigLoaderTest {
    private static final String PROFILES = """
            {
              "schemaVersion": 1,
              "defaultProfileId": "fast",
              "profiles": [
                {
                  "id": "fast",
                  "displayName": "Fast OpenRouter",
                  "enabled": true,
                  "protocol": "openai_chat",
                  "baseUrl": "https://openrouter.ai/api/v1",
                  "model": "vendor/model-a",
                  "apiKeyEnv": "OPENROUTER_KEY",
                  "contextWindowTokens": 256000,
                  "maxOutputTokens": 8192,
                  "connectTimeoutSeconds": 30,
                  "requestTimeoutSeconds": 300
                },
                {
                  "id": "local",
                  "displayName": "Local Model",
                  "enabled": false,
                  "protocol": "openai_chat",
                  "baseUrl": "http://127.0.0.1:11434/v1",
                  "model": "local/model",
                  "apiKeyEnv": "LOCAL_MODEL_KEY",
                  "maxOutputTokens": 4096,
                  "connectTimeoutSeconds": 10,
                  "requestTimeoutSeconds": 120
                }
              ]
            }
            """;

    private final ModelProfilesConfigLoader loader = new ModelProfilesConfigLoader();

    @Test
    void loadsOrderedProfilesAndRetainsDisabledOrUnresolvedDefinitions() {
        ModelProfilesConfigLoader.Load loaded = success(loader.load(
                new StringReader(PROFILES), Map.of("OPENROUTER_KEY", "super-secret"))).value();

        assertEquals("fast", loaded.config().defaultProfileId());
        assertEquals(List.of("fast", "local"), loaded.config().profiles().stream()
                .map(ModelProfileDefinition::id).toList());
        ResolvedModelProfile fast = loaded.profiles().getFirst();
        assertTrue(fast.available());
        assertEquals(256_000, fast.runtimeConfig().contextWindowTokens());
        assertEquals("super-secret", fast.runtimeConfig().apiKey().reveal());
        assertFalse(fast.toString().contains("super-secret"));
        assertFalse(new Gson().toJson(fast.diagnosticView()).contains("super-secret"));

        ResolvedModelProfile local = loaded.profiles().get(1);
        assertFalse(local.available());
        assertEquals("model_disabled", local.failure().code());
        assertNull(local.runtimeConfig());
    }

    @Test
    void missingContextOrSecretStaysVisibleButCannotRun() {
        String enabledWithoutContext = PROFILES
                .replace("\"enabled\": false", "\"enabled\": true");
        ModelProfilesConfigLoader.Load loaded = success(loader.load(
                new StringReader(enabledWithoutContext),
                Map.of("OPENROUTER_KEY", "key"))).value();

        ResolvedModelProfile local = loaded.profiles().get(1);
        assertEquals("invalid_model_config", local.failure().code());
        assertTrue(local.failure().message().contains("contextWindowTokens"));

        ModelProfilesConfigLoader.Load missingSecret = success(loader.load(
                new StringReader(PROFILES), Map.of())).value();
        assertEquals("model_not_configured", missingSecret.profiles().getFirst().failure().code());
        assertFalse(missingSecret.profiles().getFirst().failure().message().contains("null"));
    }

    @Test
    void rejectsInlineSecretsDuplicatesMissingDefaultFutureSchemaAndUnknownFields() {
        assertInvalid(PROFILES.replace(
                "\"apiKeyEnv\": \"OPENROUTER_KEY\"",
                "\"apiKeyEnv\": \"OPENROUTER_KEY\", \"apiKey\": \"forbidden\""));
        assertInvalid(PROFILES.replace("\"id\": \"local\"", "\"id\": \"fast\""));
        assertInvalid(PROFILES.replace("\"defaultProfileId\": \"fast\"",
                "\"defaultProfileId\": \"missing\""));
        assertInvalid(PROFILES.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
        assertInvalid(PROFILES.replace("\"displayName\": \"Fast OpenRouter\"",
                "\"displayName\": \"Fast OpenRouter\", \"surprise\": true"));
    }

    @Test
    void importsLegacyOnlyWhenNewFileIsAbsent(@TempDir Path directory) throws Exception {
        Path profiles = directory.resolve("models.json");
        Path legacy = directory.resolve("model.json");
        Files.writeString(legacy, """
                {"protocol":"anthropic_messages","baseUrl":"https://example.test/v1",
                 "model":"legacy-model","apiKey":"legacy-secret",
                 "contextWindowTokens":128000,"maxOutputTokens":4096}
                """);

        ModelProfilesConfigLoader.Load imported = success(loader.load(
                profiles, legacy, Map.of())).value();
        assertTrue(imported.legacy());
        assertEquals("default", imported.config().defaultProfileId());
        assertEquals("legacy-model", imported.profiles().getFirst().definition().model());
        assertEquals("legacy-secret", imported.profiles().getFirst().runtimeConfig().apiKey().reveal());

        Files.writeString(profiles, PROFILES);
        ModelProfilesConfigLoader.Load preferred = success(loader.load(
                profiles, legacy, Map.of("OPENROUTER_KEY", "new-secret"))).value();
        assertFalse(preferred.legacy());
        assertEquals("vendor/model-a", preferred.profiles().getFirst().definition().model());
    }

    @Test
    void missingBothFormatsIsExplicit(@TempDir Path directory) {
        ToolResult.Failure<ModelProfilesConfigLoader.Load> failure = failure(loader.load(
                directory.resolve("models.json"),
                directory.resolve("model.json"),
                Map.of()));
        assertEquals("model_not_configured", failure.code());
    }

    private void assertInvalid(String json) {
        assertEquals("invalid_model_config", failure(loader.load(
                new StringReader(json), Map.of("OPENROUTER_KEY", "key"))).code());
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ModelProfilesConfigLoader.Load> success(
            ToolResult<ModelProfilesConfigLoader.Load> result) {
        return (ToolResult.Success<ModelProfilesConfigLoader.Load>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ModelProfilesConfigLoader.Load> failure(
            ToolResult<ModelProfilesConfigLoader.Load> result) {
        return (ToolResult.Failure<ModelProfilesConfigLoader.Load>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
