package dev.tomewisp.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class RecipeProviderStatusTest {
    @Test
    void unavailableProviderRetainsDiagnosticWithoutFabricatingGeneration() {
        RecipeProviderStatus status = RecipeProviderStatus.from(
                RecipeProviderSnapshot.unavailable(
                        "viewer:jei", "runtime_unavailable", "JEI is not ready"));

        assertEquals(RecipeProviderState.UNAVAILABLE, status.state());
        assertNull(status.generation());
        assertEquals(0, status.recipeCount());
        assertEquals("runtime_unavailable", status.diagnostics().getFirst().code());
    }

    @Test
    void failedProviderRetainsDiagnosticWithoutFabricatingGeneration() {
        RecipeProviderStatus status = RecipeProviderStatus.from(
                RecipeProviderSnapshot.failed(
                        "viewer:rei", "capture_failed", "REI capture failed"));

        assertEquals(RecipeProviderState.FAILED, status.state());
        assertNull(status.generation());
        assertEquals("capture_failed", status.diagnostics().getFirst().code());
    }
}
