package dev.tomewisp.model.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.net.HttpExchangeRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OpenRouterMetadataResolverTest {
    private static final Instant CAPTURED = Instant.parse("2026-07-18T12:00:00Z");
    private static final String MODEL = "anthropic/claude-sonnet";
    private static final String RESPONSE = """
            {"data":[
              {"id":"other/model","canonical_slug":"other/model","context_length":128000},
              {"id":"anthropic/claude-sonnet",
               "canonical_slug":"anthropic/claude-sonnet-4.5",
               "context_length":256000,
               "top_provider":{"context_length":256000,"max_completion_tokens":64000}}
            ]}
            """;

    @Test
    void resolvesExactOfficialFieldsAndKeepsCanonicalIdentity() {
        AtomicReference<HttpExchangeRequest> capturedRequest = new AtomicReference<>();
        OpenRouterMetadataResolver resolver = resolver((request, cancellation) -> {
            capturedRequest.set(request);
            return CompletableFuture.completedFuture(
                    new OpenRouterMetadataResolver.Response(200, RESPONSE));
        }, SecretValue.of("top-secret"));

        ModelMetadataResolution resolution = resolver.resolve(
                MODEL, null, null, new CancellationSignal()).join();

        assertTrue(resolution.successful());
        assertEquals(256_000, resolution.contextWindowTokens());
        assertEquals(64_000, resolution.maxOutputTokens());
        assertEquals("anthropic/claude-sonnet-4.5",
                resolution.metadata().canonicalModelId());
        assertEquals(CAPTURED, resolution.metadata().capturedAt());
        assertEquals(OpenRouterMetadataResolver.MODELS_URI, capturedRequest.get().uri());
        assertEquals("Bearer top-secret", capturedRequest.get().headers()
                .get("Authorization")
                .getFirst());
        assertFalse(resolution.toString().contains("top-secret"));
    }

    @Test
    void missingOutputLimitIsUnknownAndExplicitValuesWin() {
        String response = """
                {"data":[{"id":"anthropic/claude-sonnet",
                  "canonical_slug":"anthropic/claude-sonnet-4.5",
                  "context_length":256000,"top_provider":null}]}
                """;
        OpenRouterMetadataResolver resolver = resolver(response(200, response), null);

        ModelMetadataResolution discovered = resolver.resolve(
                MODEL, null, null, new CancellationSignal()).join();
        ModelMetadataResolution explicit = resolver.resolve(
                MODEL, 512_000, 8_192, new CancellationSignal()).join();

        assertNull(discovered.maxOutputTokens());
        assertEquals(512_000, explicit.contextWindowTokens());
        assertEquals(8_192, explicit.maxOutputTokens());
    }

    @Test
    void missingDuplicateOverflowAndMalformedPayloadsFailClosed() {
        assertFailure("metadata_not_found", "{\"data\":[]}");
        assertFailure("metadata_invalid", """
                {"data":[
                  {"id":"anthropic/claude-sonnet","canonical_slug":"a","context_length":1},
                  {"id":"anthropic/claude-sonnet","canonical_slug":"a","context_length":1}
                ]}
                """);
        assertFailure("metadata_invalid", """
                {"data":[{"id":"anthropic/claude-sonnet",
                  "canonical_slug":"a","context_length":999999999999999999}]}
                """);
        assertFailure("metadata_invalid", "{\"data\":{}}");
    }

    @Test
    void transportHttpAndCancellationFailuresAreRedacted() {
        String rawBody = "secret-provider-body";
        ModelMetadataResolution http = resolver(response(503, rawBody), null)
                .resolve(MODEL, null, null, new CancellationSignal()).join();
        ModelMetadataResolution transport = resolver((request, cancellation) ->
                CompletableFuture.failedFuture(new RuntimeException(rawBody)), null)
                .resolve(MODEL, null, null, new CancellationSignal()).join();
        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();
        ModelMetadataResolution cancelled = resolver(response(200, RESPONSE), null)
                .resolve(MODEL, null, null, cancellation).join();

        assertEquals("metadata_unavailable", http.failure().code());
        assertEquals("metadata_unavailable", transport.failure().code());
        assertEquals("metadata_cancelled", cancelled.failure().code());
        assertFalse(http.toString().contains(rawBody));
        assertFalse(transport.toString().contains(rawBody));
    }

    private static void assertFailure(String code, String body) {
        ModelMetadataResolution resolution = resolver(response(200, body), null)
                .resolve(MODEL, null, null, new CancellationSignal()).join();
        assertEquals(code, resolution.failure().code());
    }

    private static OpenRouterMetadataResolver resolver(
            OpenRouterMetadataResolver.Transport transport, SecretValue key) {
        return new OpenRouterMetadataResolver(
                transport,
                Clock.fixed(CAPTURED, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                key);
    }

    private static OpenRouterMetadataResolver.Transport response(int status, String body) {
        return (request, cancellation) -> CompletableFuture.completedFuture(
                new OpenRouterMetadataResolver.Response(status, body));
    }
}
