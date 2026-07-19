package dev.tomewisp.settings.model;

import dev.tomewisp.client.ClientModelRuntimeRegistry;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfileSettingsStore;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ModelProfilesConfigWriter;
import dev.tomewisp.model.config.CredentialReference;
import dev.tomewisp.model.config.CredentialResolver;
import dev.tomewisp.model.config.LocalCredentialStore;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.model.metadata.ModelMetadata;
import dev.tomewisp.settings.ClientSettingsService;
import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Secure model-domain adapter used by the common settings coordinator. */
public final class ModelSettingsBackend implements ClientSettingsService.ModelActions {
    private final Path profilesPath;
    private final Path legacyPath;
    private final Supplier<Map<String, String>> environment;
    private final ClientModelRuntimeRegistry registry;
    private final LocalCredentialStore credentialStore;
    private final CredentialResolver credentials;
    private final ModelConnectionProbe probe;
    private final ModelProfileSettingsStore store;
    private final ModelProfilesConfigLoader loader = new ModelProfilesConfigLoader();
    private final ModelProfilesConfigWriter writer = new ModelProfilesConfigWriter();

    public ModelSettingsBackend(
            Path profilesPath,
            Path legacyPath,
            Supplier<Map<String, String>> environment,
            ClientModelRuntimeRegistry registry,
            ModelConnectionProbe probe) {
        this(
                profilesPath,
                legacyPath,
                environment,
                registry,
                probe,
                new LocalCredentialStore(
                        profilesPath.toAbsolutePath().normalize().resolveSibling(
                                "credentials.sqlite3"),
                        java.time.Clock.systemUTC()));
    }

    public ModelSettingsBackend(
            Path profilesPath,
            Path legacyPath,
            Supplier<Map<String, String>> environment,
            ClientModelRuntimeRegistry registry,
            ModelConnectionProbe probe,
            LocalCredentialStore credentialStore) {
        this.profilesPath = Objects.requireNonNull(profilesPath, "profilesPath")
                .toAbsolutePath().normalize();
        this.legacyPath = Objects.requireNonNull(legacyPath, "legacyPath")
                .toAbsolutePath().normalize();
        this.environment = Objects.requireNonNull(environment, "environment");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.credentials = CredentialResolver.composite(
                credentialStore, environmentSnapshot());
        this.store = new ModelProfileSettingsStore(this.profilesPath);
    }

    public ToolResult<ClientSettingsService.ModelState> loadInitial(
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        ToolResult<ModelProfilesConfigLoader.Load> loaded = loader.load(
                profilesPath,
                legacyPath,
                credentials,
                environmentSnapshot(),
                Map.copyOf(metadata));
        if (loaded instanceof ToolResult.Success<ModelProfilesConfigLoader.Load> success) {
            collectUnreferenced(success.value().config());
        }
        return mapLoad(loaded);
    }

    public Set<String> presentEnvironmentNames() {
        TreeSet<String> names = new TreeSet<>();
        environmentSnapshot().forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                names.add(name);
            }
        });
        return java.util.Collections.unmodifiableSet(names);
    }

    @Override
    public ToolResult<ClientSettingsService.ModelState> save(
            ModelProfilesConfig candidate,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        return save(candidate, null, null, metadata);
    }

    @Override
    public ToolResult<ClientSettingsService.ModelState> save(
            ModelProfilesConfig candidate,
            String replacementProfileId,
            SecretValue replacement,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        CredentialReference inserted = null;
        ModelProfilesConfig prepared = candidate;
        if (replacement != null) {
            ToolResult<CredentialReference> created = credentialStore.insert(replacement);
            if (created instanceof ToolResult.Failure<CredentialReference> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            inserted = ((ToolResult.Success<CredentialReference>) created).value();
            try {
                prepared = replaceCredential(candidate, replacementProfileId, inserted);
            } catch (RuntimeException failure) {
                credentialStore.deleteIfUnreferenced(inserted, Set.of());
                return new ToolResult.Failure<>(
                        "invalid_model_config", "Unable to prepare model profile settings");
            }
        }
        ToolResult<ModelProfileSettingsStore.Saved> saved = store.save(
                prepared, credentials, Map.copyOf(metadata), registry);
        if (saved instanceof ToolResult.Failure<ModelProfileSettingsStore.Saved> failure) {
            if (inserted != null) {
                credentialStore.deleteIfUnreferenced(inserted, Set.of());
            }
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ModelProfileSettingsStore.Saved value =
                ((ToolResult.Success<ModelProfileSettingsStore.Saved>) saved).value();
        collectUnreferenced(value.config());
        return new ToolResult.Success<>(new ClientSettingsService.ModelState(
                value.config(),
                value.profiles().stream()
                        .map(ModelProfileSettingsView.Resolution::from)
                        .toList()));
    }

    @Override
    public ToolResult<ClientSettingsService.ModelState> reload(
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        ToolResult<ModelProfilesConfigLoader.Load> loaded = loader.load(
                profilesPath,
                legacyPath,
                credentials,
                environmentSnapshot(),
                Map.copyOf(metadata));
        if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ModelProfilesConfigLoader.Load value =
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
        ClientModelRuntimeRegistry.PreparedReplacement prepared = registry.prepare(value);
        prepared.publish();
        return new ToolResult.Success<>(state(value));
    }

    @Override
    public ToolResult<ResolvedModelProfile> resolve(
            ModelProfileDefinition candidate,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        return resolve(candidate, null, metadata);
    }

    @Override
    public ToolResult<ResolvedModelProfile> resolve(
            ModelProfileDefinition candidate,
            SecretValue replacement,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        ModelProfilesConfig isolated = new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION,
                candidate.id(),
                java.util.List.of(candidate));
        ToolResult<ModelProfilesConfigLoader.Load> loaded = replacement == null
                ? decode(isolated, metadata)
                : decode(isolated, reference -> {
                    CredentialReference expected = CredentialReference.parse(
                            candidate.credentialRef());
                    return expected.equals(reference)
                            ? new ToolResult.Success<>(replacement)
                            : credentials.resolve(reference);
                }, metadata);
        if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        return new ToolResult.Success<>(
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded)
                        .value().profiles().getFirst());
    }

    @Override
    public ToolResult<ClientSettingsService.PreparedModels> prepare(
            ModelProfilesConfig candidate,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        ToolResult<ModelProfilesConfigLoader.Load> loaded = decode(candidate, metadata);
        if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ModelProfilesConfigLoader.Load value =
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
        ClientModelRuntimeRegistry.PreparedReplacement prepared = registry.prepare(value);
        return new ToolResult.Success<>(new ClientSettingsService.PreparedModels(
                state(value), prepared::publish));
    }

    @Override
    public CompletableFuture<ModelConnectionResult> probe(
            ResolvedModelProfile profile,
            CancellationSignal cancellation) {
        return probe.test(profile, cancellation);
    }

    private ToolResult<ModelProfilesConfigLoader.Load> decode(
            ModelProfilesConfig config,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        return decode(config, credentials, metadata);
    }

    private ToolResult<ModelProfilesConfigLoader.Load> decode(
            ModelProfilesConfig config,
            CredentialResolver resolver,
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        try {
            return loader.load(
                    new StringReader(writer.encode(config)),
                    resolver,
                    Map.copyOf(metadata));
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_model_config", "Unable to prepare model profile settings");
        }
    }

    private Map<String, String> environmentSnapshot() {
        return Map.copyOf(environment.get());
    }

    public void closeCredentials() {
        credentialStore.close();
    }

    public void collectUnreferencedCredentials(ModelProfilesConfig config) {
        collectUnreferenced(Objects.requireNonNull(config, "config"));
    }

    private static ModelProfilesConfig replaceCredential(
            ModelProfilesConfig candidate,
            String profileId,
            CredentialReference reference) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("replacement profile id is required");
        }
        boolean found = false;
        java.util.List<ModelProfileDefinition> definitions = new java.util.ArrayList<>();
        for (ModelProfileDefinition definition : candidate.profiles()) {
            if (!definition.id().equals(profileId)) {
                definitions.add(definition);
                continue;
            }
            found = true;
            definitions.add(new ModelProfileDefinition(
                    definition.id(),
                    definition.displayName(),
                    definition.enabled(),
                    definition.protocol(),
                    definition.baseUri(),
                    definition.model(),
                    reference.encoded(),
                    definition.contextWindowTokens(),
                    definition.maxOutputTokens(),
                    definition.connectTimeout(),
                    definition.requestTimeout(),
                    definition.metadata()));
        }
        if (!found) {
            throw new IllegalArgumentException("replacement profile is unavailable");
        }
        return new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION,
                candidate.defaultProfileId(),
                definitions);
    }

    private void collectUnreferenced(ModelProfilesConfig config) {
        java.util.Set<CredentialReference> retained = new java.util.HashSet<>();
        for (ModelProfileDefinition definition : config.profiles()) {
            CredentialReference reference = CredentialReference.parse(definition.credentialRef());
            if (reference.kind() == CredentialReference.Kind.LOCAL) {
                retained.add(reference);
            }
        }
        credentialStore.collectUnreferenced(retained);
    }

    private static ToolResult<ClientSettingsService.ModelState> mapLoad(
            ToolResult<ModelProfilesConfigLoader.Load> loaded) {
        if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        return new ToolResult.Success<>(state(
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value()));
    }

    private static ClientSettingsService.ModelState state(
            ModelProfilesConfigLoader.Load load) {
        return new ClientSettingsService.ModelState(
                load.config(),
                load.profiles().stream()
                        .map(ModelProfileSettingsView.Resolution::from)
                        .toList());
    }
}
