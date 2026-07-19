package dev.openallay.recipe.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.RecipeReference;
import dev.openallay.recipe.RecipeNavigationResult;
import dev.openallay.recipe.RecipeViewerNavigator;
import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecipeClientRuntimeTest {
    @TempDir Path directory;

    @Test
    void loadsGenericConfigAndRetainsLastValidStateOnReloadFailure() throws Exception {
        Path path = directory.resolve("recipes.json");
        Files.writeString(path, """
                {"schemaVersion":2,"visibility":"unlocked_only","preferredViewer":"viewer:rei",
                 "disabledSources":["minecraft:client_recipe_book","viewer:jei"]}
                """);
        RecipeClientRuntime runtime = new RecipeClientRuntime(path);

        assertEquals(RecipeVisibilityPolicy.UNLOCKED_ONLY, runtime.config().visibility());
        assertFalse(runtime.sourceEnabled("minecraft:client_recipe_book"));
        assertFalse(runtime.sourceEnabled("viewer:jei"));
        assertTrue(runtime.sourceEnabled("viewer:rei"));

        Files.writeString(path, "{}");
        assertInstanceOf(ToolResult.Failure.class, runtime.reload());
        assertEquals("viewer:rei", runtime.config().preferredViewer());
        assertTrue(runtime.failure().isPresent());
    }

    @Test
    void explicitUnavailableViewerNeverFallsBack() {
        AtomicInteger jeiRecipes = new AtomicInteger();
        RecipeClientConfig config = config("viewer:emi", Set.of());
        RecipeClientRuntime runtime = RecipeClientRuntime.forTest(
                config, () -> List.of(navigator("viewer:jei", true, jeiRecipes)));

        assertEquals("viewer_unavailable", runtime.openRecipes("minecraft:stick").code());
        assertEquals(0, jeiRecipes.get());
    }

    @Test
    void autoUsesKnownRankThenStableIdAndHonorsSourceDisablement() {
        AtomicInteger jeiRecipes = new AtomicInteger();
        AtomicInteger reiRecipes = new AtomicInteger();
        AtomicInteger futureRecipes = new AtomicInteger();
        RecipeClientRuntime runtime = RecipeClientRuntime.forTest(
                config(RecipeClientConfig.AUTO, Set.of("viewer:jei")),
                () -> List.of(
                        navigator("future:zeta", true, futureRecipes),
                        navigator("viewer:rei", false, reiRecipes),
                        navigator("viewer:jei", true, jeiRecipes)));

        assertTrue(runtime.openRecipes("minecraft:iron_ingot").opened());
        assertEquals(0, jeiRecipes.get());
        assertEquals(1, reiRecipes.get());
        assertEquals(0, futureRecipes.get());
    }

    @Test
    void exactOwnershipSelectsOnlyEnabledViewer() {
        RecipeViewerNavigator jei = navigator("viewer:jei", true, new AtomicInteger());
        RecipeViewerNavigator rei = navigator("viewer:rei", false, new AtomicInteger());
        RecipeClientRuntime runtime = RecipeClientRuntime.forTest(
                config("viewer:rei", Set.of()), () -> List.of(jei, rei));

        assertFalse(runtime.supportsExact(reference("viewer:rei")));
        assertTrue(runtime.supportsExact(reference("viewer:jei")));
        assertEquals("exact_unsupported", runtime.openExact(reference("viewer:rei")).code());
        assertTrue(runtime.openExact(reference("viewer:jei")).opened());
    }

    private static RecipeClientConfig config(String viewer, Set<String> disabled) {
        return new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                viewer,
                disabled);
    }

    private static RecipeReference reference(String sourceId) {
        return new RecipeReference(sourceId, "0".repeat(64), "test:recipe");
    }

    private static RecipeViewerNavigator navigator(
            String id, boolean exact, AtomicInteger recipes) {
        return new RecipeViewerNavigator() {
            @Override public String viewerId() { return id; }
            @Override public boolean supportsExactRecipe() { return exact; }
            @Override public RecipeNavigationResult openRecipes(String itemId) {
                recipes.incrementAndGet();
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
}
