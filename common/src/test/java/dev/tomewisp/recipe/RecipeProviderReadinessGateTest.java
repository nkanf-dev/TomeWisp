package dev.tomewisp.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RecipeProviderReadinessGateTest {
    @Test
    void noRequiredViewerIsReadyWithoutSamplingProviders() {
        RecipeProviderReadiness readiness = new RecipeProviderReadinessGate()
                .evaluate(List.of(), List.of());

        assertEquals(RecipeProviderReadiness.State.READY, readiness.state());
    }

    @Test
    void waitsForEveryCurrentSnapshotAndResamplesAfterViewerReload() {
        AtomicInteger firstCaptures = new AtomicInteger();
        AtomicInteger secondCaptures = new AtomicInteger();
        AtomicBoolean secondReady = new AtomicBoolean();
        RecipeKnowledgeProvider first = provider("viewer:jei", firstCaptures, () -> available("viewer:jei"));
        RecipeKnowledgeProvider second = provider("viewer:rei", secondCaptures, () -> secondReady.get()
                ? available("viewer:rei")
                : RecipeProviderSnapshot.unavailable(
                        "viewer:rei", "runtime_unavailable", "REI is still loading"));
        RecipeProviderReadinessGate gate = new RecipeProviderReadinessGate();

        assertEquals(RecipeProviderReadiness.State.WAITING,
                gate.evaluate(List.of("viewer:jei", "viewer:rei"), List.of(first, second)).state());
        assertEquals(1, firstCaptures.get());
        assertEquals(1, secondCaptures.get());

        secondReady.set(true);
        assertEquals(RecipeProviderReadiness.State.READY,
                gate.evaluate(List.of("viewer:jei", "viewer:rei"), List.of(first, second)).state());
        assertEquals(2, firstCaptures.get());
        assertEquals(2, secondCaptures.get());

        secondReady.set(false);
        assertEquals(RecipeProviderReadiness.State.WAITING,
                gate.evaluate(List.of("viewer:jei", "viewer:rei"), List.of(first, second)).state());
        assertEquals(3, firstCaptures.get());
        assertEquals(3, secondCaptures.get());
    }

    @Test
    void missingProviderWaitsWhileFailedProviderFailsExplicitly() {
        RecipeProviderReadinessGate missingGate = new RecipeProviderReadinessGate();
        RecipeProviderReadiness missing = missingGate.evaluate(List.of("viewer:jei"), List.of());
        assertEquals(RecipeProviderReadiness.State.WAITING, missing.state());
        assertEquals("recipe_provider_loading", missing.code());

        RecipeKnowledgeProvider failed = provider(
                "viewer:jei",
                new AtomicInteger(),
                () -> RecipeProviderSnapshot.failed(
                        "viewer:jei", "capture_failed", "JEI capture failed"));
        RecipeProviderReadiness failure = new RecipeProviderReadinessGate()
                .evaluate(List.of("viewer:jei"), List.of(failed));
        assertEquals(RecipeProviderReadiness.State.FAILED, failure.state());
        assertEquals("recipe_provider_failed", failure.code());
        assertEquals("JEI capture failed", failure.message());
    }

    private static RecipeKnowledgeProvider provider(
            String sourceId,
            AtomicInteger captures,
            java.util.function.Supplier<RecipeProviderSnapshot> snapshot) {
        return new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public RecipeProviderSnapshot capture() {
                captures.incrementAndGet();
                return snapshot.get();
            }
        };
    }

    private static RecipeProviderSnapshot available(String sourceId) {
        var recipe = GroundedTestFixtures.ironBlockRecipe();
        return RecipeProviderSnapshot.available(
                sourceId,
                DataCompleteness.COMPLETE,
                List.of(recipe.withReference(new RecipeReference(
                        sourceId, "0".repeat(64), recipe.reference().recipeId()))),
                List.of());
    }
}
