package dev.tomewisp.model.config;

import dev.tomewisp.tool.ToolResult;
import java.util.Map;
import java.util.Objects;

/** Resolves qualified credential references only at the provider boundary. */
@FunctionalInterface
public interface CredentialResolver {
    ToolResult<SecretValue> resolve(CredentialReference reference);

    static CredentialResolver environment(Map<String, String> environment) {
        Map<String, String> snapshot = Map.copyOf(environment);
        return reference -> {
            Objects.requireNonNull(reference, "reference");
            if (reference.kind() != CredentialReference.Kind.ENVIRONMENT) {
                return new ToolResult.Failure<>(
                        "credential_not_found", "The stored credential is unavailable");
            }
            String value = snapshot.get(reference.value());
            if (value == null || value.isBlank()) {
                return new ToolResult.Failure<>(
                        "model_not_configured", "The configured credential is unavailable");
            }
            return new ToolResult.Success<>(SecretValue.of(value));
        };
    }

    static CredentialResolver composite(
            CredentialResolver local,
            Map<String, String> environment) {
        Objects.requireNonNull(local, "local");
        CredentialResolver external = environment(environment);
        return reference -> reference.kind() == CredentialReference.Kind.LOCAL
                ? local.resolve(reference)
                : external.resolve(reference);
    }
}
