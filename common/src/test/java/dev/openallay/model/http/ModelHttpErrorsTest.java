package dev.openallay.model.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.model.ModelClientException;
import dev.openallay.model.ModelRateLimitException;
import dev.openallay.net.HttpResponseHeaders;
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
    void classifiesContextAndProtocolRejectionsWithoutExposingProviderText() {
        ModelClientException context = assertThrows(
                ModelClientException.class,
                () -> ModelHttpErrors.requireSuccess(
                        400,
                        new HttpResponseHeaders(Map.of()),
                        body("{\"error\":{\"code\":\"context_length_exceeded\","
                                + "\"message\":\"private token details\"}}")));
        ModelClientException protocol = assertThrows(
                ModelClientException.class,
                () -> ModelHttpErrors.requireSuccess(
                        400,
                        new HttpResponseHeaders(Map.of()),
                        body("{\"error\":{\"type\":\"invalid_request_error\","
                                + "\"message\":\"tool_call_id must match private-id\"}}")));

        assertEquals("model_context_rejected", context.failure().code());
        assertEquals("Model context was rejected by the endpoint", context.failure().message());
        assertEquals("model_protocol_rejected", protocol.failure().code());
        assertEquals("Model tool-call history was rejected by the endpoint", protocol.failure().message());
        assertFalse(context.toString().contains("private token details"));
        assertFalse(protocol.toString().contains("private-id"));
    }

    @Test
    void unknownBadRequestUsesStableRequestRejection() {
        ModelClientException failure = assertThrows(
                ModelClientException.class,
                () -> ModelHttpErrors.requireSuccess(
                        400,
                        new HttpResponseHeaders(Map.of()),
                        body("not-json-private-body")));

        assertEquals("model_request_rejected", failure.failure().code());
        assertEquals("Model request was rejected by the endpoint", failure.failure().message());
        assertFalse(failure.toString().contains("not-json-private-body"));
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
