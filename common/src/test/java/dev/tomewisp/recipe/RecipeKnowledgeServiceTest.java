package dev.tomewisp.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecipeKnowledgeServiceTest {
    @Test
    void providerFailureDoesNotDiscardSuccessfulSources() {
        RecipeKnowledgeProvider available = new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return "minecraft:recipe_manager";
            }

            @Override
            public RecipeProviderSnapshot capture() {
                return RecipeProviderSnapshot.available(
                        sourceId(),
                        dev.tomewisp.context.DataCompleteness.COMPLETE,
                        List.of(GroundedTestFixtures.ironBlockRecipe()),
                        List.of());
            }
        };
        RecipeKnowledgeProvider failed = new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return "viewer:jei";
            }

            @Override
            public RecipeProviderSnapshot capture() {
                throw new IllegalStateException("provider internals must not leak");
            }
        };

        RecipeSnapshot snapshot = new RecipeKnowledgeService().capture(
                GroundedTestFixtures.serverEvidence(),
                RecipeVisibilityPolicy.ALL_KNOWN,
                List.of(failed, available));

        assertEquals(1, snapshot.recipes().size());
        assertEquals(dev.tomewisp.context.DataCompleteness.PARTIAL,
                snapshot.evidence().completeness());
        assertEquals(RecipeProviderState.FAILED, snapshot.providers().getFirst().state());
        assertEquals("Recipe provider capture failed",
                snapshot.providers().getFirst().diagnostics().getFirst().message());
    }
}
