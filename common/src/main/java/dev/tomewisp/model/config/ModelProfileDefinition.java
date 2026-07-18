package dev.tomewisp.model.config;

import dev.tomewisp.agent.context.ContextBudget;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Credential-free persisted definition for one named client model profile. */
public record ModelProfileDefinition(
        String id,
        String displayName,
        boolean enabled,
        ModelProtocol protocol,
        URI baseUri,
        String model,
        String apiKeyEnv,
        Integer contextWindowTokens,
        int maxOutputTokens,
        Duration connectTimeout,
        Duration requestTimeout,
        MetadataProvenance metadata) {
    public ModelProfileDefinition {
        if (id == null || !id.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid model profile id");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(baseUri, "baseUri");
        validateUri(baseUri);
        String raw = baseUri.toString();
        baseUri = URI.create(raw.endsWith("/") ? raw : raw + "/");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (apiKeyEnv == null || !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("apiKeyEnv must be an environment variable name");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (contextWindowTokens != null) {
            new ContextBudget(contextWindowTokens, maxOutputTokens);
        }
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model timeouts must be positive");
        }
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
            throw new IllegalArgumentException(
                    "Model base URL must not contain credentials, query, or fragment");
        }
    }

    public record MetadataProvenance(
            String source,
            String upstreamModelId,
            Instant capturedAt) {
        public MetadataProvenance {
            if (source == null || source.isBlank()
                    || upstreamModelId == null || upstreamModelId.isBlank()) {
                throw new IllegalArgumentException("metadata provenance identity must not be blank");
            }
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }
}
