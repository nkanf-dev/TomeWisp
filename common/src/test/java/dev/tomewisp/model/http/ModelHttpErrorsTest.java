package dev.tomewisp.model.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelRateLimitException;
import dev.tomewisp.net.HttpResponseHeaders;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModelHttpErrorsTest {
    @Test
    void providerErrorBodyNeverEntersModelFailure() {
        ModelClientException failure = assertThrows(
                ModelClientException.class,
                () -> ModelHttpErrors.requireSuccess(
                        401,
                        new HttpResponseHeaders(Map.of()),
                        body("{\"error\":{\"message\":\"secret upstream detail\"}}")));

        assertEquals("model_http_error", failure.failure().code());
        assertEquals("Model endpoint returned HTTP 401", failure.failure().message());
        assertEquals(401, failure.failure().httpStatus());
        assertFalse(failure.toString().contains("secret upstream detail"));
    }

    @Test
    void rateLimitRetainsOnlyStatusAndParsedRetryAfter() {
        ModelRateLimitException failure = assertInstanceOf(
                ModelRateLimitException.class,
                assertThrows(ModelClientException.class, () -> ModelHttpErrors.requireSuccess(
                        429,
                        new HttpResponseHeaders(Map.of("retry-after", List.of("7"))),
                        body("private provider body"))));

        assertEquals("Model endpoint returned HTTP 429", failure.failure().message());
        assertEquals(Duration.ofSeconds(7), failure.retryAfter());
        assertFalse(failure.toString().contains("private provider body"));
    }

    private static ByteArrayInputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
