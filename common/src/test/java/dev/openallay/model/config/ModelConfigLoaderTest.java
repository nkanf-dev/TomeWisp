package dev.openallay.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.Gson;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModelConfigLoaderTest {
    private final ModelConfigLoader loader = new ModelConfigLoader();

    @Test
    void environmentOverridesFileAndSecretsNeverRender() {
        ToolResult.Success<ModelConfig> result = success(loader.load(
                new StringReader("""
                        {"protocol":"openai_chat","baseUrl":"https://example.test/v1",
                         "model":"old","apiKey":"file-secret","contextWindowTokens":128000,
                         "maxOutputTokens":1024}
                        """),
                Map.of(
                        "OPENALLAY_MODEL_PROTOCOL", "anthropic_messages",
                        "OPENALLAY_MODEL", "mimo-v2.5-pro",
                        "OPENALLAY_API_KEY", "environment-secret")));

        ModelConfig config = result.value();
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, config.protocol());
        assertEquals("mimo-v2.5-pro", config.model());
        assertEquals("environment-secret", config.apiKey().reveal());
        assertFalse(config.toString().contains("environment-secret"));
        assertFalse(new Gson().toJson(config.diagnosticView()).contains("environment-secret"));
        assertEquals("https://example.test/v1/", config.baseUri().toString());
        assertEquals(128_000, config.contextWindowTokens());
        assertEquals(128_000 - 2 * 1024, config.contextBudget().inputTokens());
    }

    @Test
    void contextWindowCanBeOverriddenAndMustExceedDoubleOutputReserve() {
        ModelConfig overridden = success(loader.load(
                new StringReader("""
                        {"protocol":"anthropic_messages","baseUrl":"https://example.test/v1",
                         "model":"m","apiKey":"k","contextWindowTokens":64000,
                         "maxOutputTokens":4096}
                        """),
                Map.of("OPENALLAY_CONTEXT_WINDOW_TOKENS", "96000"))).value();
        assertEquals(96_000, overridden.contextWindowTokens());

        assertEquals("invalid_model_config", failure(loader.load(
                new StringReader("""
                        {"protocol":"anthropic_messages","baseUrl":"https://example.test/v1",
                         "model":"m","apiKey":"k","contextWindowTokens":8192,
                         "maxOutputTokens":4096}
                        """), Map.of())).code());

        ToolResult.Failure<ModelConfig> missing = failure(loader.load(
                new StringReader("""
                        {"protocol":"anthropic_messages","baseUrl":"https://example.test/v1",
                         "model":"m","apiKey":"k"}
                        """), Map.of()));
        assertEquals(
                "contextWindowTokens is required unless trusted model metadata resolves it",
                missing.message());
    }

    @Test
    void allowsLoopbackHttpButRejectsRemoteHttpAndUnknownFields() {
        assertInstanceOf(
                ToolResult.Success.class,
                loader.load(
                        new StringReader("""
                                {"protocol":"anthropic_messages","baseUrl":"http://127.0.0.1:8080/v1",
                                 "model":"local","apiKey":"test","contextWindowTokens":128000}
                                """),
                        Map.of()));

        assertEquals(
                "invalid_model_config",
                failure(config("http://example.test/v1")).code());
        assertEquals(
                "invalid_model_config",
                failure(loader.load(
                                new StringReader("""
                                        {"protocol":"anthropic_messages","baseUrl":"https://example.test/v1",
                                         "model":"m","apiKey":"k","contextWindowTokens":128000,
                                         "surprise":true}
                                        """),
                                Map.of()))
                        .code());
    }

    private ToolResult<ModelConfig> config(String url) {
        return loader.load(
                new StringReader(("""
                        {"protocol":"anthropic_messages","baseUrl":"%s",
                         "model":"m","apiKey":"k","contextWindowTokens":128000}
                        """).formatted(url)),
                Map.of());
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ModelConfig> success(ToolResult<ModelConfig> result) {
        return (ToolResult.Success<ModelConfig>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ModelConfig> failure(ToolResult<ModelConfig> result) {
        return (ToolResult.Failure<ModelConfig>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
