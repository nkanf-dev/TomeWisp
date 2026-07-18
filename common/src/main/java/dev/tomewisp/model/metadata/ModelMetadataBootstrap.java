package dev.tomewisp.model.metadata;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.tool.ToolResult;
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
    private final Consumer<ModelProfilesConfigLoader.Load> apply;
    private final Function<ModelProfileDefinition, ModelMetadataResolver> resolverFactory;
    private final ModelProfilesConfigLoader loader = new ModelProfilesConfigLoader();
    private final List<CancellationSignal> active = new CopyOnWriteArrayList<>();
    private final AtomicReference<GuideFailure> failure = new AtomicReference<>();

    public ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            Consumer<ModelProfilesConfigLoader.Load> apply,
            Clock clock) {
        this(
                cache,
                profilesPath,
                legacyPath,
                environment,
                apply,
                profile -> new OpenRouterMetadataResolver(
                        clock,
                        profile.connectTimeout(),
                        secret(environment.get(profile.apiKeyEnv()))));
    }

    ModelMetadataBootstrap(
            ModelMetadataCache cache,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            Consumer<ModelProfilesConfigLoader.Load> apply,
            Function<ModelProfileDefinition, ModelMetadataResolver> resolverFactory) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.profilesPath = Objects.requireNonNull(profilesPath, "profilesPath");
        this.legacyPath = Objects.requireNonNull(legacyPath, "legacyPath");
        this.environment = Map.copyOf(environment);
        this.apply = Objects.requireNonNull(apply, "apply");
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
            if (snapshot.failure() != null) {
                failure.set(snapshot.failure());
                return CompletableFuture.completedFuture(null);
            }
            ModelProfilesConfigLoader.Load loaded = load(snapshot.entries());
            if (loaded == null) {
                return CompletableFuture.completedFuture(null);
            }
            apply.accept(loaded);
            failure.set(null);
            List<CompletableFuture<Void>> refreshes = new ArrayList<>();
            for (ModelProfileDefinition profile : loaded.config().profiles()) {
                if (!OpenRouterMetadataResolver.supports(profile.baseUri())) {
                    continue;
                }
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
                            return;
                        }
                        ModelProfilesConfigLoader.Load replacement = load(updated.entries());
                        if (replacement != null) {
                            apply.accept(replacement);
                        }
                    });
        });
    }

    private CompletableFuture<Void> safeRefresh(boolean force) {
        return refresh(force).exceptionally(unexpected -> {
            failure.set(new GuideFailure(
                    "metadata_unavailable", "Model metadata refresh is unavailable"));
            return null;
        });
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
                profilesPath, legacyPath, environment, metadata);
        if (result instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> invalid) {
            failure.set(new GuideFailure(invalid.code(), invalid.message()));
            return null;
        }
        return ((ToolResult.Success<ModelProfilesConfigLoader.Load>) result).value();
    }

    private static SecretValue secret(String value) {
        return value == null || value.isBlank() ? null : SecretValue.of(value);
    }
}
