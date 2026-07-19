package dev.openallay.model.metadata;

import dev.openallay.guide.GuideFailure;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.model.config.ModelProfilesConfigLoader;
import dev.openallay.model.config.CredentialReference;
import dev.openallay.model.config.CredentialResolver;
import dev.openallay.model.config.SecretValue;
import dev.openallay.tool.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Non-blocking cache apply, cache-miss refresh, and explicit manual refresh. */
public final class ModelMetadataBootstrap {
    private final ModelMetadataCache cache;
    private final Path profilesPath;
    private final Path legacyPath;
    private final Map<String, String> environment;
    private final CredentialResolver credentials;
    private final Consumer<ModelMetadataUpdate> updates;
    private final Function<ModelProfileDefinition, ModelMetadataResolver> resolverFactory;
    private final ModelProfilesConfigLoader loader = new ModelProfilesConfigLoader();
    private final List<CancellationSignal> active = new CopyOnWriteArrayList<>();
    private final AtomicReference<GuideFailure> failure = new AtomicReference<>();
    private final AtomicReference<Map<ModelMetadata.Key, ModelMetadata>> lastEntries =
            new AtomicReference<>(Map.of());

    public ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            Consumer<ModelMetadataUpdate> updates,
            Clock clock) {
        this(
                cache,
                profilesPath,
                legacyPath,
                environment,
                CredentialResolver.environment(environment),
                updates,
                profile -> new OpenRouterMetadataResolver(
                        clock,
                        profile.connectTimeout(),
                        OpenRouterMetadataResolver.supports(profile.baseUri())
                                ? secret(environment.get(environmentName(profile.credentialRef())))
                                : null));
    }

    public ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            CredentialResolver credentials,
            Consumer<ModelMetadataUpdate> updates,
            Clock clock) {
        this(
                cache,
                profilesPath,
                legacyPath,
                environment,
                credentials,
                updates,
                profile -> new OpenRouterMetadataResolver(
                        clock,
                        profile.connectTimeout(),
                        OpenRouterMetadataResolver.supports(profile.baseUri())
                                ? resolveOptional(credentials, profile.credentialRef())
                                : null));
    }

    ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            Consumer<ModelMetadataUpdate> updates,
            Function<ModelProfileDefinition, ModelMetadataResolver> resolverFactory) {
        this(
                cache,
                profilesPath,
                legacyPath,
                environment,
                CredentialResolver.environment(environment),
                updates,
                resolverFactory);
    }

    private ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            CredentialResolver credentials,
            Consumer<ModelMetadataUpdate> updates,
            Function<ModelProfileDefinition, ModelMetadataResolver> resolverFactory) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.profilesPath = Objects.requireNonNull(profilesPath, "profilesPath");
        this.legacyPath = Objects.requireNonNull(legacyPath, "legacyPath");
        this.environment = Map.copyOf(environment);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.resolverFactory = Objects.requireNonNull(resolverFactory, "resolverFactory");
    }

    public CompletableFuture<Void> start() {
        return safeRefresh(false);
    }

    public CompletableFuture<Void> refreshAll() {
        return safeRefresh(true);
    }

    public GuideFailure failure() {
        return failure.get();
    }

    public CompletableFuture<Void> closeAsync() {
        active.forEach(CancellationSignal::cancel);
        return cache.closeAsync();
    }

    private CompletableFuture<Void> refresh(boolean force) {
        return cache.load().thenCompose(snapshot -> {
            lastEntries.set(snapshot.entries());
            if (snapshot.failure() != null) {
                failure.set(snapshot.failure());
                publish(snapshot.entries(), snapshot.failure());
                return CompletableFuture.completedFuture(null);
            }
            ModelProfilesConfigLoader.Load loaded = load(snapshot.entries());
            if (loaded == null) {
                publish(snapshot.entries(), failure.get());
                return CompletableFuture.completedFuture(null);
            }
            failure.set(null);
            publish(snapshot.entries(), null);
            List<CompletableFuture<Void>> refreshes = new ArrayList<>();
            for (ModelProfileDefinition profile : loaded.config().profiles()) {
                ModelMetadata.Key key = new ModelMetadata.Key(
                        OpenRouterMetadataResolver.SOURCE, profile.model());
                if (!force && snapshot.entries().containsKey(key)) {
                    continue;
                }
                refreshes.add(refresh(profile));
            }
            if (refreshes.isEmpty()) {
                failure.set(null);
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(refreshes.toArray(CompletableFuture[]::new))
                    .thenCompose(ignored -> cache.load())
                    .thenAccept(updated -> {
                        if (updated.failure() != null) {
                            failure.set(updated.failure());
                            publish(lastEntries.get(), updated.failure());
                            return;
                        }
                        lastEntries.set(updated.entries());
                        publish(updated.entries(), failure.get());
                    });
        });
    }

    private CompletableFuture<Void> safeRefresh(boolean force) {
        return refresh(force).exceptionally(unexpected -> {
            GuideFailure unavailable = new GuideFailure(
                    "metadata_unavailable", "Model metadata refresh is unavailable");
            failure.set(unavailable);
            publish(lastEntries.get(), unavailable);
            return null;
        });
    }

    private void publish(
            Map<ModelMetadata.Key, ModelMetadata> entries,
            GuideFailure currentFailure) {
        updates.accept(new ModelMetadataUpdate(entries, currentFailure));
    }

    private CompletableFuture<Void> refresh(ModelProfileDefinition profile) {
        CancellationSignal cancellation = new CancellationSignal();
        active.add(cancellation);
        return resolverFactory.apply(profile).resolve(
                        profile.model(),
                        profile.contextWindowTokens(),
                        profile.maxOutputTokens(),
                        cancellation)
                .thenCompose(resolution -> {
                    if (!resolution.successful()) {
                        failure.set(resolution.failure());
                        return CompletableFuture.completedFuture(null);
                    }
                    return cache.put(resolution.metadata()).thenAccept(snapshot -> {
                        if (snapshot.failure() != null) {
                            failure.set(snapshot.failure());
                        }
                    });
                })
                .whenComplete((ignored, ignoredFailure) -> active.remove(cancellation));
    }

    private ModelProfilesConfigLoader.Load load(
            Map<ModelMetadata.Key, ModelMetadata> metadata) {
        ToolResult<ModelProfilesConfigLoader.Load> result = loader.load(
                profilesPath, legacyPath, credentials, environment, metadata);
        if (result instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> invalid) {
            failure.set(new GuideFailure(invalid.code(), invalid.message()));
            return null;
        }
        return ((ToolResult.Success<ModelProfilesConfigLoader.Load>) result).value();
    }

    private static SecretValue secret(String value) {
        return value == null || value.isBlank() ? null : SecretValue.of(value);
    }

    private static String environmentName(String encoded) {
        try {
            CredentialReference reference = CredentialReference.parse(encoded);
            return reference.kind() == CredentialReference.Kind.ENVIRONMENT
                    ? reference.value()
                    : "";
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static SecretValue resolveOptional(
            CredentialResolver credentials, String encoded) {
        try {
            ToolResult<SecretValue> resolved = credentials.resolve(
                    CredentialReference.parse(encoded));
            return resolved instanceof ToolResult.Success<SecretValue> success
                    ? success.value()
                    : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
