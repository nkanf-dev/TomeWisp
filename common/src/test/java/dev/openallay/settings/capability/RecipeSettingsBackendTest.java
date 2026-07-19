package dev.openallay.settings.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.RecipeReference;
import dev.openallay.recipe.RecipeNavigationResult;
import dev.openallay.recipe.RecipeViewerNavigator;
import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.recipe.config.RecipeClientRuntime;
import dev.openallay.settings.SettingsWriteException;
import dev.openallay.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecipeSettingsBackendTest {
    @TempDir Path temporary;

    @Test
    void sourceDisappearanceRetainsPreferenceAndReappearanceRestoresAvailability() {
        Path path = temporary.resolve("recipes.json");
        RecipeClientRuntime runtime = new RecipeClientRuntime(path);
        List<String> providers = new ArrayList<>(List.of("viewer:rei"));
        List<RecipeViewerNavigator> navigators = new ArrayList<>(List.of(navigator("viewer:rei")));
        RecipeSettingsBackend backend = new RecipeSettingsBackend(
                path, runtime, () -> providers, () -> navigators,
                new dev.openallay.settings.AtomicSettingsFile()::replace);
        RecipeClientConfig candidate = new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                "viewer:rei",
                Set.of("future:viewer"));

        RecipeSettingsView saved = success(backend.saveRecipes(candidate));

        assertTrue(saved.preferredViewerAvailable());
        assertEquals(Set.of("future:viewer"), saved.unknownDisabledSources());
        assertTrue(saved.sources().stream().anyMatch(source ->
                source.id().equals("viewer:rei") && source.viewer() && source.exactNavigation()));
        providers.clear();
        navigators.clear();
        assertFalse(backend.currentView().preferredViewerAvailable());
        assertEquals("viewer:rei", backend.currentView().config().preferredViewer());
        providers.add("viewer:rei");
        navigators.add(navigator("viewer:rei"));
        assertTrue(backend.currentView().preferredViewerAvailable());
    }

    @Test
    void failedAtomicMoveRetainsFileAndRuntime() throws Exception {
        Path path = temporary.resolve("recipes.json");
        RecipeClientRuntime runtime = new RecipeClientRuntime(path);
        RecipeSettingsBackend working = new RecipeSettingsBackend(
                path, runtime, List::of, List::of,
                new dev.openallay.settings.AtomicSettingsFile()::replace);
        RecipeClientConfig prior = new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                RecipeClientConfig.AUTO,
                Set.of("prior:source"));
        success(working.saveRecipes(prior));
        String priorBytes = Files.readString(path);
        RecipeSettingsBackend failing = new RecipeSettingsBackend(
                path, runtime, List::of, List::of,
                (ignoredPath, ignoredContents) -> {
                    throw new SettingsWriteException();
                });
        RecipeClientConfig candidate = new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.UNLOCKED_ONLY,
                RecipeClientConfig.AUTO,
                Set.of());

        ToolResult.Failure<RecipeSettingsView> failure = failure(
                failing.saveRecipes(candidate));

        assertEquals("settings_write_failed", failure.code());
        assertEquals(priorBytes, Files.readString(path));
        assertEquals(prior, runtime.config());
    }

    private static RecipeViewerNavigator navigator(String id) {
        return new RecipeViewerNavigator() {
            @Override public String viewerId() { return id; }
            @Override public boolean supportsExactRecipe() { return true; }
            @Override public RecipeNavigationResult openRecipes(String itemId) {
                return RecipeNavigationResult.success();
            }
            @Override public RecipeNavigationResult openUsages(String itemId) {
                return RecipeNavigationResult.success();
            }
            @Override public RecipeNavigationResult openExact(RecipeReference reference) {
                return RecipeNavigationResult.success();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static RecipeSettingsView success(ToolResult<RecipeSettingsView> result) {
        return ((ToolResult.Success<RecipeSettingsView>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<RecipeSettingsView> failure(
            ToolResult<RecipeSettingsView> result) {
        return (ToolResult.Failure<RecipeSettingsView>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
