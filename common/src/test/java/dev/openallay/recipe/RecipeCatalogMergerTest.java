package dev.openallay.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.IngredientAlternativeSnapshot;
import dev.openallay.context.IngredientRequirementSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeLayoutSnapshot;
import dev.openallay.context.RecipeOutputSnapshot;
import dev.openallay.context.RecipeProcessingSnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.context.RecipeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RecipeCatalogMergerTest {
    private static final String PLACEHOLDER = "0".repeat(64);

    @Test
    void providerGenerationIsPermutationStableAndContentSensitive() {
        RecipeEntrySnapshot iron = recipe(
                "viewer:jei", "test:iron", "test:iron", "minecraft:iron_block",
                RecipeUnlockState.UNKNOWN, DataAuthority.INTEGRATION_API);
        RecipeEntrySnapshot gold = recipe(
                "viewer:jei", "test:gold", "test:gold", "minecraft:gold_block",
                RecipeUnlockState.UNKNOWN, DataAuthority.INTEGRATION_API);

        RecipeProviderSnapshot first = RecipeProviderSnapshot.available(
                "viewer:jei", DataCompleteness.COMPLETE, List.of(iron, gold), List.of());
        RecipeProviderSnapshot reversed = RecipeProviderSnapshot.available(
                "viewer:jei", DataCompleteness.COMPLETE, List.of(gold, iron), List.of());
        RecipeProviderSnapshot changed = RecipeProviderSnapshot.available(
                "viewer:jei",
                DataCompleteness.COMPLETE,
                List.of(recipe(
                        "viewer:jei", "test:iron", "test:iron", "minecraft:diamond_block",
                        RecipeUnlockState.UNKNOWN, DataAuthority.INTEGRATION_API), gold),
                List.of());

        assertEquals(first.generation(), reversed.generation());
        assertNotEquals(first.generation(), changed.generation());
    }

    @Test
    void groupsIdenticalSemanticsRetainsEvidenceAndFiltersUnlocks() {
        RecipeProviderSnapshot vanilla = RecipeProviderSnapshot.available(
                "minecraft:client_recipe_book",
                DataCompleteness.PARTIAL,
                List.of(recipe(
                        "minecraft:client_recipe_book",
                        "test:iron",
                        "test:iron",
                        "minecraft:iron_block",
                        RecipeUnlockState.UNLOCKED,
                        DataAuthority.CLIENT_VISIBLE)),
                List.of());
        RecipeProviderSnapshot jei = RecipeProviderSnapshot.available(
                "viewer:jei",
                DataCompleteness.COMPLETE,
                List.of(recipe(
                        "viewer:jei",
                        "test:iron",
                        "test:iron",
                        "minecraft:iron_block",
                        RecipeUnlockState.UNKNOWN,
                        DataAuthority.INTEGRATION_API)),
                List.of());
        RecipeProviderSnapshot failed = new RecipeProviderSnapshot(
                "viewer:rei",
                null,
                RecipeProviderState.FAILED,
                DataCompleteness.UNKNOWN,
                List.of(),
                List.of(new RecipeProviderDiagnostic(
                        "viewer:rei", "capture_failed", "fixture failure")));

        RecipeCatalogMerger merger = new RecipeCatalogMerger();
        RecipeSnapshot all = merger.merge(
                catalogEvidence(), RecipeVisibilityPolicy.ALL_KNOWN, List.of(failed, jei, vanilla));
        RecipeSnapshot reversed = merger.merge(
                catalogEvidence(), RecipeVisibilityPolicy.ALL_KNOWN, List.of(vanilla, jei, failed));
        RecipeSnapshot unlocked = merger.merge(
                catalogEvidence(), RecipeVisibilityPolicy.UNLOCKED_ONLY, List.of(vanilla, jei, failed));

        assertEquals(DataCompleteness.PARTIAL, all.evidence().completeness());
        assertEquals(2, all.recipes().size());
        assertEquals(1, all.groups().size());
        assertEquals(2, all.groups().getFirst().references().size());
        assertEquals(2, new RecipeCatalog(all)
                .search(new RecipeCatalog.Query(null, "minecraft:iron_block", null, null))
                .getFirst()
                .references()
                .size());
        assertEquals("minecraft:client_recipe_book",
                all.groups().getFirst().representative().reference().sourceId());
        assertEquals(all.recipes(), reversed.recipes());
        assertEquals(all.groups(), reversed.groups());
        assertEquals(1, unlocked.recipes().size());
        assertEquals("minecraft:client_recipe_book",
                unlocked.recipes().getFirst().reference().sourceId());
    }

    @Test
    void preservesSameIdConflictsAsSeparateVariants() {
        RecipeProviderSnapshot jei = provider(
                "viewer:jei", "minecraft:iron_block", DataAuthority.INTEGRATION_API);
        RecipeProviderSnapshot rei = provider(
                "viewer:rei", "minecraft:gold_block", DataAuthority.INTEGRATION_API);

        RecipeSnapshot snapshot = new RecipeCatalogMerger().merge(
                catalogEvidence(), RecipeVisibilityPolicy.ALL_KNOWN, List.of(jei, rei));

        assertEquals(2, snapshot.recipes().size());
        assertEquals(2, snapshot.groups().size());
        assertEquals(1, snapshot.diagnostics().size());
        assertEquals("recipe_id_conflict", snapshot.diagnostics().getFirst().code());
        assertEquals(2, snapshot.diagnostics().getFirst().references().size());
    }

    private static RecipeProviderSnapshot provider(
            String sourceId, String output, DataAuthority authority) {
        return RecipeProviderSnapshot.available(
                sourceId,
                DataCompleteness.COMPLETE,
                List.of(recipe(
                        sourceId,
                        "test:shared",
                        "test:shared",
                        output,
                        RecipeUnlockState.UNKNOWN,
                        authority)),
                List.of());
    }

    private static RecipeEntrySnapshot recipe(
            String sourceId,
            String recipeId,
            String id,
            String output,
            RecipeUnlockState unlock,
            DataAuthority authority) {
        EvidenceMetadata evidence = new EvidenceMetadata(
                authority,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                sourceId,
                sourceId,
                "test",
                "common-test",
                Map.of());
        return new RecipeEntrySnapshot(
                new RecipeReference(sourceId, PLACEHOLDER, recipeId),
                id,
                "minecraft:crafting",
                new RecipeLayoutSnapshot(1, 1, true),
                "minecraft:crafting_table",
                List.of(new IngredientRequirementSnapshot(
                        "input-0",
                        1,
                        true,
                        List.of(new IngredientAlternativeSnapshot(
                                "item", "minecraft:iron_ingot", List.of("minecraft:iron_ingot"))))),
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(new ItemStackSnapshot(output, 1, output), 1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.<String, JsonObject>of(),
                unlock,
                evidence);
    }

    private static EvidenceMetadata catalogEvidence() {
        return new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.UNKNOWN,
                Instant.EPOCH,
                "openallay:recipe_catalog",
                "openallay:recipe_catalog",
                "test",
                "common-test",
                Map.of());
    }
}
