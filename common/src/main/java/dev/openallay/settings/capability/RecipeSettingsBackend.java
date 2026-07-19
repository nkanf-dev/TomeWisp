package dev.openallay.settings.capability;

import dev.openallay.recipe.RecipeViewerNavigator;
import dev.openallay.recipe.RecipeViewerNavigatorRegistry;
import dev.openallay.recipe.RecipeViewerProviderRegistry;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.recipe.config.RecipeClientConfigLoader;
import dev.openallay.recipe.config.RecipeClientConfigWriter;
import dev.openallay.recipe.config.RecipeClientRuntime;
import dev.openallay.settings.AtomicSettingsFile;
import dev.openallay.settings.ClientSettingsService;
import dev.openallay.settings.SettingsWriteException;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Atomically persists recipe Tool settings and projects registered source IDs. */
public final class RecipeSettingsBackend implements ClientSettingsService.RecipeActions {
    public static final String VANILLA_SOURCE_ID = "minecraft:client_recipe_book";

    @FunctionalInterface
    interface FileReplacement {
        void replace(Path target, String contents);
    }

    private final Path path;
    private final RecipeClientRuntime runtime;
    private final Supplier<List<String>> providerIds;
    private final Supplier<List<RecipeViewerNavigator>> navigators;
    private final FileReplacement files;
    private final RecipeClientConfigLoader loader = new RecipeClientConfigLoader();
    private final RecipeClientConfigWriter writer = new RecipeClientConfigWriter();

    public RecipeSettingsBackend(Path path, RecipeClientRuntime runtime) {
        this(
                path,
                runtime,
                RecipeViewerProviderRegistry::sourceIds,
                RecipeViewerNavigatorRegistry::navigators,
                new AtomicSettingsFile()::replace);
    }

    RecipeSettingsBackend(
            Path path,
            RecipeClientRuntime runtime,
            Supplier<List<String>> providerIds,
            Supplier<List<RecipeViewerNavigator>> navigators,
            FileReplacement files) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.providerIds = Objects.requireNonNull(providerIds, "providerIds");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
        this.files = Objects.requireNonNull(files, "files");
    }

    public RecipeSettingsView currentView() {
        return view(runtime.config());
    }

    @Override
    public ToolResult<RecipeSettingsView> saveRecipes(RecipeClientConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String encoded;
        RecipeClientConfig validated;
        try {
            encoded = writer.encode(candidate);
            ToolResult<RecipeClientConfig> decoded = loader.load(new StringReader(encoded));
            if (decoded instanceof ToolResult.Failure<RecipeClientConfig> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            validated = ((ToolResult.Success<RecipeClientConfig>) decoded).value();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_recipe_config", "Unable to prepare recipe settings");
        }
        try {
            files.replace(path, encoded);
        } catch (SettingsWriteException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("settings_write_failed", "Unable to save settings");
        }
        runtime.replace(validated);
        return new ToolResult.Success<>(view(validated));
    }

    @Override
    public ToolResult<RecipeSettingsView> reloadRecipes() {
        ToolResult<RecipeClientConfig> loaded = runtime.reload();
        if (loaded instanceof ToolResult.Failure<RecipeClientConfig> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        return new ToolResult.Success<>(view(
                ((ToolResult.Success<RecipeClientConfig>) loaded).value()));
    }

    private RecipeSettingsView view(RecipeClientConfig config) {
        Map<String, RecipeViewerNavigator> viewerById = new TreeMap<>();
        for (RecipeViewerNavigator navigator : List.copyOf(navigators.get())) {
            viewerById.put(navigator.viewerId(), navigator);
        }
        Set<String> known = new TreeSet<>();
        known.add(VANILLA_SOURCE_ID);
        known.addAll(List.copyOf(providerIds.get()));
        known.addAll(viewerById.keySet());
        List<RecipeSettingsView.Source> sources = new ArrayList<>();
        for (String sourceId : known) {
            RecipeViewerNavigator navigator = viewerById.get(sourceId);
            sources.add(new RecipeSettingsView.Source(
                    sourceId,
                    true,
                    !config.disabledSources().contains(sourceId),
                    navigator != null,
                    navigator != null && navigator.supportsExactRecipe()));
        }
        Set<String> unknown = new HashSet<>(config.disabledSources());
        unknown.removeAll(known);
        boolean preferredAvailable = RecipeClientConfig.AUTO.equals(config.preferredViewer())
                || (viewerById.containsKey(config.preferredViewer())
                        && !config.disabledSources().contains(config.preferredViewer()));
        return new RecipeSettingsView(config, sources, unknown, preferredAvailable);
    }
}
