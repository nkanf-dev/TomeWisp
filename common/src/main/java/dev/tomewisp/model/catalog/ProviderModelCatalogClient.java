package dev.tomewisp.model.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.net.HttpExchangeRequest;
import dev.tomewisp.net.HttpTransport;
import dev.tomewisp.net.HttpTransportPolicy;
import dev.tomewisp.net.JdkHttpTransport;
import dev.tomewisp.tool.ToolResult;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Strict configuration-layer adapter for OpenAI-style provider model catalogs. */
public final class ProviderModelCatalogClient {
    private final HttpTransport transport;

    public ProviderModelCatalogClient(Duration connectTimeout) {
        this(new JdkHttpTransport(new HttpTransportPolicy(
                connectTimeout, "tomewisp-model-catalog-http")));
    }

    public ProviderModelCatalogClient(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public CompletableFuture<ToolResult<ModelCatalog>> fetch(
            ModelCatalogRequest request,
            SecretValue credential,
            CancellationSignal cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return CompletableFuture.completedFuture(cancelled());
        }
        HttpExchangeRequest.Builder encoded = HttpExchangeRequest.newBuilder(
                        request.baseUri().resolve("models"))
                .timeout(request.requestTimeout())
                .header("accept", "application/json")
                .get();
        if (request.protocol() == ModelProtocol.OPENAI_CHAT) {
            encoded.header("authorization", "Bearer " + credential.reveal());
        } else {
            encoded.header("x-api-key", credential.reveal())
                    // Anthropic-compatible inference gateways commonly expose an OpenAI-style
                    // /models route even when /messages uses x-api-key authentication.
                    .header("authorization", "Bearer " + credential.reveal())
                    .header("anthropic-version", "2023-06-01");
        }
        CompletableFuture<Response> response;
        try {
            response = transport.execute(
                    encoded.build(),
                    cancellation,
                    (status, headers, body) -> new Response(status, read(body)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(classify(failure, cancellation));
        }
        return response.handle((received, failure) -> {
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            if (failure != null || received == null) {
                return classify(failure, cancellation);
            }
            if (received.status() != 200) {
                return statusFailure(received.status());
            }
            try {
                return new ToolResult.Success<>(decode(received.body()));
            } catch (RuntimeException malformed) {
                return failure(
                        "model_catalog_malformed",
                        "The model provider returned an invalid model catalog");
            }
        });
    }

    private static ModelCatalog decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("catalog root");
        }
        JsonElement data = parsed.getAsJsonObject().get("data");
        if (data == null || !data.isJsonArray()) {
            throw new IllegalArgumentException("catalog data");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("catalog entry");
            }
            JsonElement id = element.getAsJsonObject().get("id");
            if (id == null || !id.isJsonPrimitive()
                    || !id.getAsJsonPrimitive().isString()
                    || id.getAsString().isBlank()) {
                throw new IllegalArgumentException("catalog model id");
            }
            ids.add(id.getAsString());
        }
        return new ModelCatalog(ids.stream().toList());
    }

    private static String read(InputStream body) throws java.io.IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static ToolResult.Failure<ModelCatalog> statusFailure(int status) {
        return switch (status) {
            case 401, 403 -> failure(
                    "model_catalog_auth_failed",
                    "The model provider rejected catalog authentication");
            case 429 -> failure(
                    "model_catalog_rate_limited",
                    "The model provider rate-limited the catalog request");
            default -> failure(
                    "model_catalog_unavailable",
                    "The model provider catalog is unavailable");
        };
    }

    private static ToolResult.Failure<ModelCatalog> classify(
            Throwable thrown, CancellationSignal cancellation) {
        if (cancellation.isCancelled()) {
            return cancelled();
        }
        Throwable cause = unwrap(thrown);
        if (cause instanceof CancellationException) {
            return cancelled();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return failure(
                    "model_catalog_timeout", "The model catalog request timed out");
        }
        return failure(
                "model_catalog_transport_failed",
                "The model provider catalog could not be reached");
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        if (current == null) {
            return null;
        }
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ToolResult.Failure<ModelCatalog> cancelled() {
        return failure("model_catalog_cancelled", "The model catalog request was cancelled");
    }

    private static ToolResult.Failure<ModelCatalog> failure(String code, String message) {
        return new ToolResult.Failure<>(code, message);
    }

    private record Response(int status, String body) {
        private Response {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public String toString() {
            return "Response[status=" + status + "]";
        }
    }
}
