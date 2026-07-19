package dev.tomewisp.model.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.net.HttpExchangeRequest;
import dev.tomewisp.net.HttpResponseHeaders;
import dev.tomewisp.net.HttpTransport;
import dev.tomewisp.tool.ToolResult;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProviderModelCatalogClientTest {
    private static final String SECRET = "must-not-escape";

    @Test
    void openAiCatalogUsesResolvedModelsUriAndReturnsOrderedUniqueIds() {
        AtomicReference<HttpExchangeRequest> captured = new AtomicReference<>();
        ProviderModelCatalogClient client = client(captured, 200, """
                {"object":"list","data":[
                  {"id":"vendor/z"},{"id":"vendor/a"},{"id":"vendor/z"}
                ]}
                """);

        ToolResult<ModelCatalog> result = client.fetch(
                request(ModelProtocol.OPENAI_CHAT),
                SecretValue.of(SECRET),
                new CancellationSignal()).join();

        ModelCatalog catalog = success(result);
        assertEquals(java.util.List.of("vendor/z", "vendor/a"), catalog.modelIds());
        assertEquals(URI.create("https://provider.example/v1/models"), captured.get().uri());
        assertEquals("GET", captured.get().method());
        assertEquals("Bearer " + SECRET, captured.get().headers()
                .get("authorization").getFirst());
        assertFalse(result.toString().contains(SECRET));
        assertFalse(captured.get().toString().contains(SECRET));
        assertFalse(request(ModelProtocol.OPENAI_CHAT).toString().contains("provider.example"));
    }

    @Test
    void anthropicCatalogUsesProviderApiKeyHeaders() {
        AtomicReference<HttpExchangeRequest> captured = new AtomicReference<>();
        ToolResult<ModelCatalog> result = client(captured, 200, "{\"data\":[]}")
                .fetch(
                        request(ModelProtocol.ANTHROPIC_MESSAGES),
                        SecretValue.of(SECRET),
                        new CancellationSignal())
                .join();

        assertInstanceOf(ToolResult.Success.class, result);
        assertEquals(SECRET, captured.get().headers().get("x-api-key").getFirst());
        assertEquals("2023-06-01",
                captured.get().headers().get("anthropic-version").getFirst());
        assertEquals("Bearer " + SECRET,
                captured.get().headers().get("authorization").getFirst());
    }

    @Test
    void classifiesStatusesTimeoutCancellationAndMalformedBodiesWithoutRawContent() {
        assertFailure(401, "provider-secret-body", "model_catalog_auth_failed");
        assertFailure(403, "provider-secret-body", "model_catalog_auth_failed");
        assertFailure(429, "provider-secret-body", "model_catalog_rate_limited");
        assertFailure(503, "provider-secret-body", "model_catalog_unavailable");
        assertFailure(200, "{\"data\":[{\"id\":\"\"}]}", "model_catalog_malformed");
        assertFailure(200, "{\"data\":{}}", "model_catalog_malformed");

        HttpTransport timedOut = new HttpTransport() {
            @Override
            public <T> CompletableFuture<T> execute(
                    HttpExchangeRequest request,
                    dev.tomewisp.net.HttpCancellation cancellation,
                    ResponseDecoder<T> decoder) {
                return CompletableFuture.failedFuture(
                        new HttpTimeoutException("raw-timeout"));
            }
        };
        ToolResult<ModelCatalog> timeout = new ProviderModelCatalogClient(timedOut)
                .fetch(
                        request(ModelProtocol.OPENAI_CHAT),
                        SecretValue.of(SECRET),
                        new CancellationSignal())
                .join();
        assertEquals("model_catalog_timeout",
                assertInstanceOf(ToolResult.Failure.class, timeout).code());

        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();
        ToolResult<ModelCatalog> cancelled = client(new AtomicReference<>(), 200, "{\"data\":[]}")
                .fetch(request(ModelProtocol.OPENAI_CHAT), SecretValue.of(SECRET), cancellation)
                .join();
        assertEquals("model_catalog_cancelled",
                assertInstanceOf(ToolResult.Failure.class, cancelled).code());
        assertFalse(timeout.toString().contains("raw-timeout"));
        assertFalse(timeout.toString().contains(SECRET));
    }

    private static void assertFailure(int status, String body, String code) {
        ToolResult<ModelCatalog> result = client(new AtomicReference<>(), status, body)
                .fetch(
                        request(ModelProtocol.OPENAI_CHAT),
                        SecretValue.of(SECRET),
                        new CancellationSignal())
                .join();
        assertEquals(code, assertInstanceOf(ToolResult.Failure.class, result).code());
        assertFalse(result.toString().contains(body));
        assertFalse(result.toString().contains(SECRET));
    }

    private static ProviderModelCatalogClient client(
            AtomicReference<HttpExchangeRequest> captured, int status, String body) {
        HttpTransport transport = new HttpTransport() {
            @Override
            public <T> CompletableFuture<T> execute(
                    HttpExchangeRequest request,
                    dev.tomewisp.net.HttpCancellation cancellation,
                    ResponseDecoder<T> decoder) {
                captured.set(request);
                try {
                    return CompletableFuture.completedFuture(decoder.decode(
                            status,
                            new HttpResponseHeaders(Map.of()),
                            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
                } catch (java.io.IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        };
        return new ProviderModelCatalogClient(transport);
    }

    private static ModelCatalogRequest request(ModelProtocol protocol) {
        return new ModelCatalogRequest(
                "profile",
                protocol,
                URI.create("https://provider.example/v1"),
                "env:MODEL_KEY",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20));
    }

    @SuppressWarnings("unchecked")
    private static ModelCatalog success(ToolResult<ModelCatalog> result) {
        return ((ToolResult.Success<ModelCatalog>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }
}
