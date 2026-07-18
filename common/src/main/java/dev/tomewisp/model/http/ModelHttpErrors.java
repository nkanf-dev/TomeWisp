package dev.tomewisp.model.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import dev.tomewisp.net.HttpResponseHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import dev.tomewisp.model.ModelRateLimitException;

public final class ModelHttpErrors {
    private ModelHttpErrors() {}

    public static void requireSuccess(int status, HttpResponseHeaders headers, InputStream body)
            throws IOException {
        if (status >= 200 && status < 300) {
            return;
        }
        String response = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        String message = "Model endpoint returned HTTP " + status;
        try {
            JsonElement json = JsonParser.parseString(response);
            if (json.isJsonObject() && json.getAsJsonObject().has("error")) {
                JsonElement error = json.getAsJsonObject().get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    message += ": " + error.getAsJsonObject().get("message").getAsString();
                } else if (error.isJsonPrimitive()) {
                    message += ": " + error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Do not echo arbitrary provider bodies into player-facing diagnostics.
        }
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
