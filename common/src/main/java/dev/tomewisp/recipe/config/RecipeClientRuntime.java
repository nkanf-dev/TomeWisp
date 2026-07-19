package dev.tomewisp.recipe.config;

import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.recipe.RecipeNavigationResult;
import dev.tomewisp.recipe.RecipeViewerNavigator;
import dev.tomewisp.recipe.RecipeViewerNavigatorRegistry;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Shared client recipe preferences and typed viewer navigation intents. */
public final class RecipeClientRuntime {
    private final Path path;
    private final RecipeClientConfigLoader loader;
    private final Supplier<List<RecipeViewerNavigator>> navigators;
    private volatile RecipeClientConfig config;
    private volatile ToolResult.Failure<RecipeClientConfig> failure;

    public RecipeClientRuntime(Path path) {
        this(path, new RecipeClientConfigLoader(), RecipeViewerNavigatorRegistry::navigators);
    }

    RecipeClientRuntime(
            Path path,
            RecipeClientConfigLoader loader,
            Supplier<List<RecipeViewerNavigator>> navigators) {
        this.path = Objects.requireNonNull(path, "path");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
        this.config = RecipeClientConfig.defaults();
        reload();
    }

    private RecipeClientRuntime(
            RecipeClientConfig config, Supplier<List<RecipeViewerNavigator>> navigators) {
        this.path = null;
        this.loader = null;
        this.config = Objects.requireNonNull(config, "config");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
    }

    public static RecipeClientRuntime defaults() {
        return new RecipeClientRuntime(
                RecipeClientConfig.defaults(), RecipeViewerNavigatorRegistry::navigators);
    }

    static RecipeClientRuntime forTest(
            RecipeClientConfig config, Supplier<List<RecipeViewerNavigator>> navigators) {
        return new RecipeClientRuntime(config, navigators);
    }

    public RecipeClientConfig config() {
        return config;
    }

    public Optional<ToolResult.Failure<RecipeClientConfig>> failure() {
        return Optional.ofNullable(failure);
    }

    /** Publishes an already validated persisted candidate for future captures/navigation. */
    public synchronized void replace(RecipeClientConfig replacement) {
        config = Objects.requireNonNull(replacement, "replacement");
        failure = null;
    }

    public synchronized ToolResult<RecipeClientConfig> reload() {
        if (path == null) {
            return new ToolResult.Success<>(config);
        }
        ToolResult<RecipeClientConfig> loaded = loader.load(path);
        if (loaded instanceof ToolResult.Success<RecipeClientConfig> success) {
            config = success.value();
            failure = null;
        } else {
            failure = (ToolResult.Failure<RecipeClientConfig>) loaded;
        }
        return loaded;
    }

    public boolean sourceEnabled(String sourceId) {
        return !config.disabledSources().contains(RecipeReference.requireSourceId(sourceId));
    }

    public boolean supportsExact(RecipeReference reference) {
        Objects.requireNonNull(reference, "reference");
        return navigator(reference.sourceId())
                .map(RecipeViewerNavigator::supportsExactRecipe)
                .orElse(false);
    }

    public boolean canBrowse() {
        return preferredNavigator().isPresent();
    }

    public RecipeNavigationResult openRecipes(String itemId) {
        return preferredNavigator()
                .map(value -> value.openRecipes(itemId))
                .orElseGet(this::unavailable);
    }

    public RecipeNavigationResult openUsages(String itemId) {
        return preferredNavigator()
                .map(value -> value.openUsages(itemId))
                .orElseGet(this::unavailable);
    }

    public RecipeNavigationResult openExact(RecipeReference reference) {
        Objects.requireNonNull(reference, "reference");
        Optional<RecipeViewerNavigator> navigator = navigator(reference.sourceId());
        if (navigator.isEmpty()) {
            return RecipeNavigationResult.failed(
                    "exact_unsupported", "No enabled viewer owns this recipe reference");
        }
        if (!navigator.orElseThrow().supportsExactRecipe()) {
            return RecipeNavigationResult.failed(
                    "exact_unsupported", "The selected viewer cannot open an exact recipe");
        }
        return navigator.orElseThrow().openExact(reference);
    }

    private Optional<RecipeViewerNavigator> preferredNavigator() {
        List<RecipeViewerNavigator> available = availableNavigators();
        if (!RecipeClientConfig.AUTO.equals(config.preferredViewer())) {
            return available.stream()
                    .filter(value -> value.viewerId().equals(config.preferredViewer()))
                    .findFirst();
        }
        return available.stream().min(Comparator
                .comparingInt((RecipeViewerNavigator value) -> viewerRank(value.viewerId()))
                .thenComparing(RecipeViewerNavigator::viewerId));
    }

    private Optional<RecipeViewerNavigator> navigator(String sourceId) {
        if (!sourceEnabled(sourceId)) {
            return Optional.empty();
        }
        return availableNavigators().stream()
                .filter(value -> value.viewerId().equals(sourceId))
                .findFirst();
    }

    private List<RecipeViewerNavigator> availableNavigators() {
        return List.copyOf(navigators.get()).stream()
                .filter(value -> sourceEnabled(value.viewerId()))
                .sorted(Comparator.comparing(RecipeViewerNavigator::viewerId))
                .toList();
    }

    private RecipeNavigationResult unavailable() {
        return RecipeNavigationResult.failed(
                "viewer_unavailable", "No enabled recipe viewer is available");
    }

    private static int viewerRank(String viewerId) {
        return switch (viewerId) {
            case "viewer:jei" -> 0;
            case "viewer:rei" -> 1;
            case "viewer:emi" -> 2;
            default -> 100;
        };
    }
}
