package dev.openallay.recipe.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecipeClientConfigLoaderTest {
    private final RecipeClientConfigLoader loader = new RecipeClientConfigLoader();

    @Test
    void missingFileUsesAllKnownGenericDefaultsWithoutWriting(@TempDir Path directory) {
        Path path = directory.resolve("missing.json");
        RecipeClientConfig config = success(loader.load(path));

        assertEquals(RecipeVisibilityPolicy.ALL_KNOWN, config.visibility());
        assertEquals(RecipeClientConfig.AUTO, config.preferredViewer());
        assertEquals(Set.of(), config.disabledSources());
        assertFalse(Files.exists(path));
    }

    @Test
    void loadsGenericStableSourceIdsAndRetainsUnknownDisabledIds() {
        RecipeClientConfig config = success(loader.load(new StringReader("""
                {"schemaVersion":2,"visibility":"ALL_KNOWN",
                 "preferredViewer":"viewer:emi",
                 "disabledSources":["viewer:jei","future:viewer"]}
                """)));

        assertEquals("viewer:emi", config.preferredViewer());
        assertEquals(Set.of("viewer:jei", "future:viewer"), config.disabledSources());
    }

    @Test
    void oldAdapterSpecificSchemaFailsWithoutMigration() {
        assertInvalid("""
                {"schemaVersion":1,"visibility":"all_known","preferredViewer":"auto",
                 "sources":{"vanilla":true,"jei":true,"rei":true}}
                """);
    }

    @Test
    void rejectsMissingUnknownDuplicateBlankMalformedAndNonIntegralFields() {
        assertInvalid("""
                {"schemaVersion":2,"visibility":"all_known","preferredViewer":"auto"}
                """);
        assertInvalid("""
                {"schemaVersion":2,"visibility":"all_known","preferredViewer":"auto",
                 "disabledSources":[],"extra":true}
                """);
        assertInvalid("""
                {"schemaVersion":2.5,"visibility":"all_known","preferredViewer":"auto",
                 "disabledSources":[]}
                """);
        assertInvalid("""
                {"schemaVersion":2,"visibility":"all_known","preferredViewer":"auto",
                 "disabledSources":["viewer:jei","viewer:jei"]}
                """);
        assertInvalid("""
                {"schemaVersion":2,"visibility":"all_known","preferredViewer":"auto",
                 "disabledSources":[""]}
                """);
        assertInvalid("""
                {"schemaVersion":2,"visibility":"all_known","preferredViewer":"not an id",
                 "disabledSources":[]}
                """);
    }

    private void assertInvalid(String json) {
        ToolResult<RecipeClientConfig> result = loader.load(new StringReader(json));
        if (!(result instanceof ToolResult.Failure<RecipeClientConfig> failure)) {
            throw new AssertionError("expected invalid recipe settings");
        }
        assertEquals("invalid_recipe_config", failure.code());
    }

    private static RecipeClientConfig success(ToolResult<RecipeClientConfig> result) {
        if (result instanceof ToolResult.Success<RecipeClientConfig> success) {
            return success.value();
        }
        throw new AssertionError("expected valid recipe settings");
    }
}
