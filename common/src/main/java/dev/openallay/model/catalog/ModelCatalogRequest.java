package dev.openallay.model.catalog;

import dev.openallay.model.config.CredentialReference;
import dev.openallay.model.config.ModelProtocol;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Credential-free configuration-layer request for one provider model catalog. */
public record ModelCatalogRequest(
        String profileId,
        ModelProtocol protocol,
        URI baseUri,
        String credentialRef,
        Duration connectTimeout,
        Duration requestTimeout) {
    public ModelCatalogRequest {
        if (profileId == null || !profileId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid model profile id");
        }
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(baseUri, "baseUri");
        validateUri(baseUri);
        String raw = baseUri.toString();
        baseUri = URI.create(raw.endsWith("/") ? raw : raw + "/");
        credentialRef = CredentialReference.parse(credentialRef).encoded();
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model catalog timeouts must be positive");
        }
    }

    @Override
    public String toString() {
        return "ModelCatalogRequest[profileId=" + profileId + ", protocol=" + protocol + "]";
    }

    private static void validateUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("Remote model catalog endpoints require HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Model catalog base URL must not contain credentials, query, or fragment");
        }
    }
}
