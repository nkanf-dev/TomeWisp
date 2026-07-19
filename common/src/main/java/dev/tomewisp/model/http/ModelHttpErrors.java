package dev.tomewisp.model.http;

import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelRateLimitException;
import dev.tomewisp.net.HttpResponseHeaders;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class ModelHttpErrors {
    private ModelHttpErrors() {}

    public static void requireSuccess(int status, HttpResponseHeaders headers, InputStream body)
            throws IOException {
        if (status >= 200 && status < 300) {
            return;
        }
        String message = "Model endpoint returned HTTP " + status;
        if (status == 429) {
            throw new ModelRateLimitException(message, retryAfter(headers));
        }
        throw new ModelClientException(new ModelFailure("model_http_error", message, status));
    }

    private static Duration retryAfter(HttpResponseHeaders headers) {
        String value = headers.firstValue("retry-after").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Duration duration = Duration.between(
                        java.time.Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }
}
