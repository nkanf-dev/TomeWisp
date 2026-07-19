package dev.openallay.recipe.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RecipeClientConfigWriterTest {
    @Test
    void writesCanonicalSortedCredentialFreeSchemaAndRoundTrips() {
        RecipeClientConfig config = new RecipeClientConfig(
                2,
                RecipeVisibilityPolicy.UNLOCKED_ONLY,
                "viewer:rei",
                Set.of("viewer:jei", "future:viewer"));

        String encoded = new RecipeClientConfigWriter().encode(config);

        assertTrue(encoded.indexOf("future:viewer") < encoded.indexOf("viewer:jei"));
        ToolResult<RecipeClientConfig> decoded =
                new RecipeClientConfigLoader().load(new StringReader(encoded));
        if (!(decoded instanceof ToolResult.Success<RecipeClientConfig> success)) {
            throw new AssertionError("canonical recipe settings did not round trip");
        }
        assertEquals(config, success.value());
    }
}
