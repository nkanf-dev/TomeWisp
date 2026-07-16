package dev.tomewisp.model.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record ModelConfig(
        boolean enabled,
        ModelProtocol protocol,
        URI baseUri,
        String model,
        SecretValue apiKey,
        int maxOutputTokens,
        Duration connectTimeout,
        Duration requestTimeout) {
    public ModelConfig {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(baseUri, "baseUri");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model ID must not be blank");
        }
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Model timeouts must be positive");
        }
        validateUri(baseUri);
        String raw = baseUri.toString();
        baseUri = URI.create(raw.endsWith("/") ? raw : raw + "/");
    }

    public DiagnosticView diagnosticView() {
        return new DiagnosticView(
                enabled,
                protocol,
                baseUri,
                model,
                apiKey.toString(),
                maxOutputTokens,
                connectTimeout.toMillis(),
                requestTimeout.toMillis());
    }

    private static void validateUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("Remote model endpoints require HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Model base URL must not contain credentials, query, or fragment");
        }
    }

    public record DiagnosticView(
            boolean enabled,
            ModelProtocol protocol,
            URI baseUri,
            String model,
            String apiKey,
            int maxOutputTokens,
            long connectTimeoutMillis,
            long requestTimeoutMillis) {}
}
