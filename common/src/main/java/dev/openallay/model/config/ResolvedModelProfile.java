package dev.openallay.model.config;

import dev.openallay.guide.GuideFailure;
import java.net.URI;
import java.util.Objects;

/** One retained profile definition and its optional usable runtime config. */
public record ResolvedModelProfile(
        ModelProfileDefinition definition,
        ModelConfig runtimeConfig,
        GuideFailure failure,
        String canonicalModelId) {
    public ResolvedModelProfile {
        Objects.requireNonNull(definition, "definition");
        if ((runtimeConfig == null) == (failure == null)) {
            throw new IllegalArgumentException(
                    "resolved profile must contain exactly one runtime or failure");
        }
        if (canonicalModelId == null || canonicalModelId.isBlank()) {
            throw new IllegalArgumentException("canonical model ID is required");
        }
    }

    public ResolvedModelProfile(
            ModelProfileDefinition definition,
            ModelConfig runtimeConfig,
            GuideFailure failure) {
        this(definition, runtimeConfig, failure, definition.model());
    }

    public boolean available() {
        return runtimeConfig != null;
    }

    public DiagnosticView diagnosticView() {
        return new DiagnosticView(
                definition.id(),
                definition.displayName(),
                definition.enabled(),
                available(),
                definition.protocol(),
                definition.baseUri(),
                definition.model(),
                definition.credentialRef(),
                runtimeConfig != null,
                runtimeConfig == null
                        ? definition.contextWindowTokens()
                        : runtimeConfig.contextWindowTokens(),
                definition.maxOutputTokens(),
                failure);
    }

    public record DiagnosticView(
            String id,
            String displayName,
            boolean enabled,
            boolean available,
            ModelProtocol protocol,
            URI baseUri,
            String model,
            String credentialRef,
            boolean apiKeyPresent,
            Integer contextWindowTokens,
            int maxOutputTokens,
            GuideFailure failure) {}
}
