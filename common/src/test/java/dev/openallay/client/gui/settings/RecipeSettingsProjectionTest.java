package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.settings.capability.RecipeSettingsView;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RecipeSettingsProjectionTest {
    @Test
    void projectsOnlyActuallyRegisteredSourcesAndHidesIdsInNormalMode() {
        RecipeSettingsProjection projection = RecipeSettingsProjection.from(
                view(), RecipeClientConfig.defaults(), false);

        assertEquals(List.of("minecraft:client_recipe_book", "viewer:rei"),
                projection.sources().stream()
                        .map(RecipeSettingsProjection.SourceRow::actionId).toList());
        assertTrue(projection.sources().stream().allMatch(row -> row.debugId() == null));
        assertFalse(projection.sources().stream()
                .anyMatch(row -> row.actionId().equals("viewer:emi")));
        assertEquals(1, projection.retainedUnknownCount());
    }

    @Test
    void sourceVisibilityAndPreferredViewerDraftsRemainGenericStableIds() {
        RecipeSettingsProjection projection = RecipeSettingsProjection.from(
                view(), RecipeClientConfig.defaults(), true);

        RecipeClientConfig preferred = projection.cyclePreferredViewer();
        RecipeClientConfig disabled = ((ToolResult.Success<RecipeClientConfig>)
                projection.toggleSource("viewer:rei")).value();
        RecipeClientConfig visibility = RecipeSettingsProjection.from(
                view(), preferred, true).cycleVisibility();

        assertEquals(Set.of("viewer:rei"), disabled.disabledSources());
        assertEquals("viewer:rei", preferred.preferredViewer());
        assertEquals(RecipeVisibilityPolicy.UNLOCKED_ONLY, visibility.visibility());
        assertEquals("viewer:rei", projection.sources().getLast().debugId());
        assertEquals("minecraft:client_recipe_book", projection.sources().getFirst().debugId());
    }

    @Test
    void preferredCycleSkipsUnavailableOrDisabledViewersButCanLeaveStalePreference() {
        RecipeClientConfig stale = new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                "viewer:rei",
                Set.of("viewer:rei"));
        RecipeSettingsProjection projection = RecipeSettingsProjection.from(view(), stale, false);

        assertFalse(projection.preferredViewerAvailable());
        assertEquals(RecipeClientConfig.AUTO, projection.cyclePreferredViewer().preferredViewer());
    }

    private static RecipeSettingsView view() {
        return new RecipeSettingsView(
                RecipeClientConfig.defaults(),
                List.of(
                        new RecipeSettingsView.Source(
                                "minecraft:client_recipe_book", true, true, false, false),
                        new RecipeSettingsView.Source(
                                "viewer:rei", true, true, true, true)),
                Set.of("future:viewer"),
                true);
    }
}
