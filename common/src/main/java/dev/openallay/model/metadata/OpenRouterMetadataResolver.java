package dev.openallay.model.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.config.SecretValue;
import dev.openallay.net.HttpTransport;
import dev.openallay.net.HttpExchangeRequest;
import dev.openallay.net.HttpTransportPolicy;
import dev.openallay.net.JdkHttpTransport;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Trusted adapter for OpenRouter's official model catalog. */
public final class OpenRouterMetadataResolver implements ModelMetadataResolver {
    public static final String SOURCE = "openrouter";
    public static final URI MODELS_URI = URI.create("https://openrouter.ai/api/v1/models");

    @FunctionalInterface
    public interface Transport {
        CompletableFuture<Response> execute(
                HttpExchangeRequest request, CancellationSignal cancellation);
    }

    public record Response(int status, String body) {
        public Response {
            Objects.requireNonNull(body, "body");
        }
    }

    private final Transport transport;
    private final Clock clock;
    private final Duration timeout;
    private final SecretValue apiKey;

    public static boolean supports(URI baseUri) {
        if (baseUri == null || !"https".equalsIgnoreCase(baseUri.getScheme())) {
            return false;
        }
        String host = baseUri.getHost() == null
                ? ""
                : baseUri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("openrouter.ai") || host.equals("eu.openrouter.ai");
    }

    public OpenRouterMetadataResolver(
            Clock clock, Duration timeout, SecretValue apiKey) {
        this(new JdkTransport(timeout), clock, timeout, apiKey);
    }

    public OpenRouterMetadataResolver(
            Transport transport, Clock clock, Duration timeout, SecretValue apiKey) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("metadata timeout must be positive");
        }
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<ModelMetadataResolution> resolve(
            String modelId,
            Integer explicitContextWindowTokens,
            Integer explicitMaxOutputTokens,
            CancellationSignal cancellation) {
        if (modelId == null || modelId.isBlank()) {
            return CompletableFuture.completedFuture(ModelMetadataResolution.failed(
                    "metadata_invalid", "The configured model ID is blank"));
        }
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return CompletableFuture.completedFuture(cancelled());
        }
        HttpExchangeRequest.Builder request = HttpExchangeRequest.newBuilder(MODELS_URI)
                .timeout(timeout)
                .header("Accept", "application/json")
                .get();
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey.reveal());
        }
        CompletableFuture<Response> response;
        try {
            response = Objects.requireNonNull(
                    transport.execute(request.build(), cancellation),
                    "metadata transport future");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(unavailable());
        }
        return response.handle((received, failure) -> {
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            if (failure != null || received == null) {
                return unavailable();
            }
            if (received.status() != 200) {
                return ModelMetadataResolution.failed(
                        "metadata_unavailable",
                        "OpenRouter metadata request failed with HTTP " + received.status());
            }
            try {
                ModelMetadata metadata = decode(received.body(), modelId);
                return ModelMetadataResolution.resolved(
                        metadata, explicitContextWindowTokens, explicitMaxOutputTokens);
            } catch (MetadataNotFound missing) {
                return ModelMetadataResolution.failed(
                        "metadata_not_found", "OpenRouter did not publish the configured model ID");
            } catch (MetadataAmbiguous ambiguous) {
                return ModelMetadataResolution.failed(
                        "metadata_ambiguous", "More than one trusted model matches the configured model ID");
            } catch (RuntimeException malformed) {
                return ModelMetadataResolution.failed(
                        "metadata_invalid", "OpenRouter returned malformed model metadata");
            }
        });
    }

    private ModelMetadata decode(String json, String requestedModelId) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("root");
        }
        JsonElement data = parsed.getAsJsonObject().get("data");
        if (data == null || !data.isJsonArray()) {
            throw new IllegalArgumentException("data");
        }
        java.util.List<JsonObject> models = new java.util.ArrayList<>();
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("model");
            }
            JsonObject model = element.getAsJsonObject();
            requiredString(model, "id");
            requiredString(model, "canonical_slug");
            models.add(model);
        }
        java.util.List<JsonObject> exact = models.stream()
                .filter(model -> requestedModelId.equals(requiredString(model, "id"))
                        || requestedModelId.equals(requiredString(model, "canonical_slug")))
                .toList();
        if (exact.size() > 1) throw new IllegalArgumentException("duplicate model");
        JsonObject match = exact.isEmpty() ? null : exact.getFirst();
        if (match == null) {
            String requestedLeaf = leaf(requestedModelId);
            java.util.List<JsonObject> aliases = models.stream()
                    .filter(model -> requestedLeaf.equals(leaf(requiredString(model, "id")))
                            || requestedLeaf.equals(leaf(requiredString(model, "canonical_slug"))))
                    .toList();
            if (aliases.size() > 1) throw new MetadataAmbiguous();
            if (aliases.size() == 1) match = aliases.getFirst();
        }
        if (match == null) {
            throw new MetadataNotFound();
        }
        int contextWindow = positiveInteger(match.get("context_length"));
        String canonical = requiredString(match, "canonical_slug");
        Integer maxOutput = null;
        JsonElement topProvider = match.get("top_provider");
        if (topProvider != null && !topProvider.isJsonNull()) {
            if (!topProvider.isJsonObject()) {
                throw new IllegalArgumentException("top_provider");
            }
            JsonElement published = topProvider.getAsJsonObject().get("max_completion_tokens");
            if (published != null && !published.isJsonNull()) {
                maxOutput = positiveInteger(published);
            }
        }
        return new ModelMetadata(
                SOURCE,
                requestedModelId,
                canonical,
                contextWindow,
                maxOutput,
                clock.instant());
    }

    private static String leaf(String modelId) {
        int separator = modelId.lastIndexOf('/');
        return separator < 0 ? modelId : modelId.substring(separator + 1);
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.getAsString();
    }

    private static int positiveInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("integer");
        }
        int parsed;
        try {
            parsed = new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("integer", failure);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("positive integer");
        }
        return parsed;
    }

    private static ModelMetadataResolution unavailable() {
        return ModelMetadataResolution.failed(
                "metadata_unavailable", "OpenRouter metadata is unavailable");
    }

    private static ModelMetadataResolution cancelled() {
        return ModelMetadataResolution.failed(
                "metadata_cancelled", "Model metadata refresh was cancelled");
    }

    private static final class MetadataNotFound extends RuntimeException {}
    private static final class MetadataAmbiguous extends RuntimeException {}

    private static final class JdkTransport implements Transport {
        private final HttpTransport transport;

        private JdkTransport(Duration timeout) {
            transport = new JdkHttpTransport(new HttpTransportPolicy(
                    timeout,
                    "openallay-config-metadata-http"));
        }

        @Override
        public CompletableFuture<Response> execute(
                HttpExchangeRequest request, CancellationSignal cancellation) {
            return transport.execute(
                    request,
                    cancellation,
                    (status, headers, body) -> new Response(
                            status,
                            new String(body.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }
}
