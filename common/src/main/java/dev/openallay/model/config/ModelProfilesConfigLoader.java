package dev.openallay.model.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.guide.GuideFailure;
import dev.openallay.model.metadata.ModelMetadata;
import dev.openallay.model.metadata.OpenRouterMetadataResolver;
import dev.openallay.tool.ToolResult;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict named-profile loader with an explicit single-profile legacy import. */
public final class ModelProfilesConfigLoader {
    private static final Set<String> ROOT_FIELDS =
            Set.of("schemaVersion", "defaultProfileId", "profiles");
    private static final Set<String> REQUIRED_PROFILE_FIELDS_V2 = Set.of(
            "id", "displayName", "enabled", "protocol", "baseUrl", "model",
            "credentialRef", "maxOutputTokens", "connectTimeoutSeconds",
            "requestTimeoutSeconds");
    private static final Set<String> REQUIRED_PROFILE_FIELDS_V1 = Set.of(
            "id", "displayName", "enabled", "protocol", "baseUrl", "model",
            "apiKeyEnv", "maxOutputTokens", "connectTimeoutSeconds",
            "requestTimeoutSeconds");
    private static final Set<String> OPTIONAL_PROFILE_FIELDS =
            Set.of("contextWindowTokens", "metadata");
    private static final Set<String> METADATA_FIELDS =
            Set.of("source", "upstreamModelId", "capturedAt");

    public record Load(
            ModelProfilesConfig config,
            List<ResolvedModelProfile> profiles,
            boolean legacy) {
        public Load {
            Objects.requireNonNull(config, "config");
            profiles = List.copyOf(profiles);
            if (profiles.size() != config.profiles().size()) {
                throw new IllegalArgumentException("every profile must have a resolution");
            }
        }
    }

    public ToolResult<Load> load(
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment) {
        return load(profilesPath, legacyPath, environment, Map.of());
    }

    public ToolResult<Load> load(
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        return load(
                profilesPath,
                legacyPath,
                CredentialResolver.environment(environment),
                environment,
                metadata);
    }

    public ToolResult<Load> load(
            Path profilesPath,
            Path legacyPath,
            CredentialResolver credentials,
            Map<String, String> legacyEnvironment,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        Objects.requireNonNull(profilesPath, "profilesPath");
        Objects.requireNonNull(legacyPath, "legacyPath");
        Objects.requireNonNull(credentials, "credentials");
        if (Files.exists(profilesPath)) {
            try (Reader reader = Files.newBufferedReader(profilesPath)) {
                return load(reader, credentials, metadata);
            } catch (IOException failure) {
                return invalid("Unable to read model profiles configuration");
            }
        }
        if (!Files.exists(legacyPath)) {
            return new ToolResult.Failure<>(
                    "model_not_configured", "No model profiles or legacy model configuration exists");
        }
        ToolResult<ModelConfig> legacy = new ModelConfigLoader().load(
                legacyPath, legacyEnvironment);
        if (legacy instanceof ToolResult.Failure<ModelConfig> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        return new ToolResult.Success<>(legacy(
                ((ToolResult.Success<ModelConfig>) legacy).value()));
    }

    public ToolResult<Load> load(Reader reader, Map<String, String> environment) {
        return load(reader, environment, Map.of());
    }

    public ToolResult<Load> load(
            Reader reader,
            Map<String, String> environment,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        return load(reader, CredentialResolver.environment(environment), metadata);
    }

    public ToolResult<Load> load(
            Reader reader,
            CredentialResolver credentials,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(credentials, "credentials");
        Map<ModelMetadata.Key, ModelMetadata> metadataCopy = Map.copyOf(metadata);
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            JsonObject root = object(parsed, "Model profiles configuration");
            exactFields(root, ROOT_FIELDS, Set.of(), "model profiles configuration");
            int schemaVersion = integer(root, "schemaVersion");
            if (schemaVersion != 1 && schemaVersion != ModelProfilesConfig.SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported model profiles schema version " + schemaVersion);
            }
            String defaultProfileId = string(root, "defaultProfileId");
            JsonArray encodedProfiles = array(root, "profiles");
            List<ModelProfileDefinition> definitions = new ArrayList<>();
            for (JsonElement encoded : encodedProfiles) {
                definitions.add(profile(object(encoded, "model profile"), schemaVersion));
            }
            ModelProfilesConfig config = new ModelProfilesConfig(
                    ModelProfilesConfig.SCHEMA_VERSION, defaultProfileId, definitions);
            List<ResolvedModelProfile> resolved = config.profiles().stream()
                    .map(profile -> resolve(profile, credentials, metadataCopy))
                    .toList();
            return new ToolResult.Success<>(new Load(config, resolved, false));
        } catch (RuntimeException failure) {
            return invalid(message(failure));
        }
    }

    private static ModelProfileDefinition profile(JsonObject object, int schemaVersion) {
        exactFields(
                object,
                schemaVersion == 1 ? REQUIRED_PROFILE_FIELDS_V1 : REQUIRED_PROFILE_FIELDS_V2,
                OPTIONAL_PROFILE_FIELDS,
                "model profile");
        ModelProfileDefinition.MetadataProvenance metadata = null;
        if (object.has("metadata") && !object.get("metadata").isJsonNull()) {
            JsonObject encoded = object(object.get("metadata"), "profile metadata");
            exactFields(encoded, METADATA_FIELDS, Set.of(), "profile metadata");
            metadata = new ModelProfileDefinition.MetadataProvenance(
                    string(encoded, "source"),
                    string(encoded, "upstreamModelId"),
                    Instant.parse(string(encoded, "capturedAt")));
        }
        return new ModelProfileDefinition(
                string(object, "id"),
                string(object, "displayName"),
                bool(object, "enabled"),
                ModelProtocol.valueOf(string(object, "protocol").toUpperCase(Locale.ROOT)),
                java.net.URI.create(string(object, "baseUrl")),
                string(object, "model"),
                schemaVersion == 1
                        ? CredentialReference.environment(string(object, "apiKeyEnv")).encoded()
                        : CredentialReference.parse(string(object, "credentialRef")).encoded(),
                optionalInteger(object, "contextWindowTokens"),
                integer(object, "maxOutputTokens"),
                Duration.ofSeconds(integer(object, "connectTimeoutSeconds")),
                Duration.ofSeconds(integer(object, "requestTimeoutSeconds")),
                metadata);
    }

    private static ResolvedModelProfile resolve(
            ModelProfileDefinition definition,
            CredentialResolver credentials,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        if (!definition.enabled()) {
            return failed(definition, "model_disabled", "This model profile is disabled");
        }
        ModelMetadata discovered = trustedMetadata(definition, metadata);
        Integer contextWindow = definition.contextWindowTokens() != null
                ? definition.contextWindowTokens()
                : discovered == null ? null : discovered.contextWindowTokens();
        if (contextWindow == null) {
            return failed(
                    definition,
                    "invalid_model_config",
                    "contextWindowTokens is required unless trusted model metadata resolves it");
        }
        ToolResult<SecretValue> resolvedCredential;
        try {
            resolvedCredential = credentials.resolve(
                    CredentialReference.parse(definition.credentialRef()));
        } catch (RuntimeException failure) {
            return failed(definition, "credential_store_unavailable", "Stored credentials are unavailable");
        }
        if (resolvedCredential instanceof ToolResult.Failure<SecretValue> failure) {
            return failed(definition, failure.code(), failure.message());
        }
        SecretValue secret = ((ToolResult.Success<SecretValue>) resolvedCredential).value();
        try {
            return new ResolvedModelProfile(
                    definition,
                    new ModelConfig(
                            true,
                            definition.protocol(),
                            definition.baseUri(),
                            definition.model(),
                            secret,
                            contextWindow,
                            definition.maxOutputTokens(),
                            definition.connectTimeout(),
                            definition.requestTimeout()),
                    null,
                    discovered == null
                            ? definition.model()
                            : discovered.canonicalModelId());
        } catch (RuntimeException failure) {
            return failed(definition, "invalid_model_config", message(failure));
        }
    }

    private static ModelMetadata trustedMetadata(
            ModelProfileDefinition definition,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        if (!OpenRouterMetadataResolver.supports(definition.baseUri())) {
            return null;
        }
        return metadata.get(new ModelMetadata.Key(
                OpenRouterMetadataResolver.SOURCE, definition.model()));
    }

    private static Load legacy(ModelConfig config) {
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "default",
                config.model(),
                config.enabled(),
                config.protocol(),
                config.baseUri(),
                config.model(),
                CredentialReference.environment("OPENALLAY_API_KEY").encoded(),
                config.contextWindowTokens(),
                config.maxOutputTokens(),
                config.connectTimeout(),
                config.requestTimeout(),
                null);
        ResolvedModelProfile resolved = config.enabled()
                ? new ResolvedModelProfile(definition, config, null)
                : failed(definition, "model_disabled", "This model profile is disabled");
        return new Load(
                new ModelProfilesConfig(
                        ModelProfilesConfig.SCHEMA_VERSION, "default", List.of(definition)),
                List.of(resolved),
                true);
    }

    private static ResolvedModelProfile failed(
            ModelProfileDefinition definition,
            String code,
            String message) {
        return new ResolvedModelProfile(definition, null, new GuideFailure(code, message));
    }

    private static <T> ToolResult<T> invalid(String message) {
        return new ToolResult.Failure<>("invalid_model_config", message);
    }

    private static void exactFields(
            JsonObject object,
            Set<String> required,
            Set<String> optional,
            String label) {
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        Set<String> missing = new java.util.TreeSet<>(required);
        missing.removeAll(object.keySet());
        Set<String> extra = new java.util.TreeSet<>(object.keySet());
        extra.removeAll(allowed);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " schema mismatch; missing=" + missing + ", extra=" + extra);
        }
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank text");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static Integer optionalInteger(JsonObject object, String field) {
        return !object.has(field) || object.get(field).isJsonNull()
                ? null : integer(object, field);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Invalid model profiles configuration"
                : failure.getMessage();
    }
}
