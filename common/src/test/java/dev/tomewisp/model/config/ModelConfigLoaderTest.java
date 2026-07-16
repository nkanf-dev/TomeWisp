package dev.tomewisp.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.Gson;
import dev.tomewisp.tool.ToolResult;
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
                         "model":"old","apiKey":"file-secret","maxOutputTokens":1024}
                        """),
                Map.of(
                        "TOMEWISP_MODEL_PROTOCOL", "anthropic_messages",
                        "TOMEWISP_MODEL", "mimo-v2.5-pro",
                        "TOMEWISP_API_KEY", "environment-secret")));

        ModelConfig config = result.value();
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, config.protocol());
        assertEquals("mimo-v2.5-pro", config.model());
        assertEquals("environment-secret", config.apiKey().reveal());
        assertFalse(config.toString().contains("environment-secret"));
        assertFalse(new Gson().toJson(config.diagnosticView()).contains("environment-secret"));
        assertEquals("https://example.test/v1/", config.baseUri().toString());
    }

    @Test
    void allowsLoopbackHttpButRejectsRemoteHttpAndUnknownFields() {
        assertInstanceOf(
                ToolResult.Success.class,
                loader.load(
                        new StringReader("""
                                {"protocol":"anthropic_messages","baseUrl":"http://127.0.0.1:8080/v1",
                                 "model":"local","apiKey":"test"}
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
                                         "model":"m","apiKey":"k","surprise":true}
                                        """),
                                Map.of()))
                        .code());
    }

    private ToolResult<ModelConfig> config(String url) {
        return loader.load(
                new StringReader(("""
                        {"protocol":"anthropic_messages","baseUrl":"%s",
                         "model":"m","apiKey":"k"}
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
