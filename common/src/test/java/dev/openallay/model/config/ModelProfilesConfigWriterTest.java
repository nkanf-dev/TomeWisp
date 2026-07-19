package dev.openallay.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModelProfilesConfigWriterTest {
    @Test
    void encodesCanonicalCredentialFreeDocumentThatRoundTrips() {
        ModelProfilesConfig config = config();

        String encoded = new ModelProfilesConfigWriter().encode(config);
        JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(List.of("schemaVersion", "defaultProfileId", "profiles"),
                root.keySet().stream().toList());
        assertEquals(List.of(
                        "id", "displayName", "enabled", "protocol", "baseUrl", "model",
                        "credentialRef", "contextWindowTokens", "maxOutputTokens",
                        "connectTimeoutSeconds", "requestTimeoutSeconds", "metadata"),
                root.getAsJsonArray("profiles").get(0).getAsJsonObject()
                        .keySet().stream().toList());
        assertTrue(encoded.endsWith(System.lineSeparator()));
        assertFalse(encoded.contains("secret-value"));
        assertFalse(encoded.contains("\"apiKey\""));

        ToolResult<ModelProfilesConfigLoader.Load> decoded = new ModelProfilesConfigLoader()
                .load(new StringReader(encoded), Map.of("MODEL_KEY", "secret-value"));
        ModelProfilesConfigLoader.Load load = success(decoded).value();
        assertEquals(config, load.config());
        assertTrue(load.profiles().getFirst().available());
    }

    @Test
    void omitsNullableOptionalFieldsInsteadOfEncodingNull() {
        ModelProfileDefinition source = config().profiles().getFirst();
        ModelProfileDefinition minimal = new ModelProfileDefinition(
                source.id(),
                source.displayName(),
                false,
                source.protocol(),
                source.baseUri(),
                source.model(),
                source.credentialRef(),
                null,
                source.maxOutputTokens(),
                source.connectTimeout(),
                source.requestTimeout(),
                null);
        String encoded = new ModelProfilesConfigWriter().encode(new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, minimal.id(), List.of(minimal)));
        JsonObject profile = JsonParser.parseString(encoded).getAsJsonObject()
                .getAsJsonArray("profiles").get(0).getAsJsonObject();

        assertFalse(profile.has("contextWindowTokens"));
        assertFalse(profile.has("metadata"));
        assertEquals(List.of(
                        "id", "displayName", "enabled", "protocol", "baseUrl", "model",
                        "credentialRef", "maxOutputTokens", "connectTimeoutSeconds",
                        "requestTimeoutSeconds"),
                profile.keySet().stream().toList());
    }

    private static ModelProfilesConfig config() {
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "main",
                "Main Model",
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example/v1"),
                "vendor/model",
                "MODEL_KEY",
                256_000,
                8_192,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                new ModelProfileDefinition.MetadataProvenance(
                        "openrouter", "vendor/model", Instant.parse("2026-07-18T00:00:00Z")));
        return new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, definition.id(), List.of(definition));
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ModelProfilesConfigLoader.Load> success(
            ToolResult<ModelProfilesConfigLoader.Load> result) {
        return (ToolResult.Success<ModelProfilesConfigLoader.Load>)
                assertInstanceOf(ToolResult.Success.class, result);
    }
}
