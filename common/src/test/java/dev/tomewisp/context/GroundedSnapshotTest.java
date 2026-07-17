package dev.tomewisp.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GroundedSnapshotTest {
    private static final EvidenceMetadata EVIDENCE = new EvidenceMetadata(
            DataAuthority.DETERMINISTIC_TEST,
            DataCompleteness.COMPLETE,
            Instant.EPOCH,
            "tomewisp:test_fixture",
            "tomewisp:test_fixture",
            "test",
            "common-test",
            Map.of());

    @Test
    void recipeAndInventoryPreserveGroundedShape() {
        IngredientRequirementSnapshot requirement = new IngredientRequirementSnapshot(
                "input-0",
                9,
                true,
                List.of(new IngredientAlternativeSnapshot(
                        "item",
                        "minecraft:iron_ingot",
                        List.of("minecraft:iron_ingot"))));
        JsonObject extension = new JsonObject();
        extension.addProperty("heat", "heated");
        Map<String, JsonObject> extensions = new HashMap<>();
        extensions.put("create:processing", extension);
        RecipeEntrySnapshot recipe = new RecipeEntrySnapshot(
                new RecipeReference("minecraft:recipe_manager", "minecraft:iron_block"),
                "minecraft:iron_block",
                "minecraft:crafting",
                new RecipeLayoutSnapshot(3, 3, true),
                "minecraft:crafting_table",
                List.of(requirement),
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(
                        new ItemStackSnapshot("minecraft:iron_block", 1, "Block of Iron"),
                        1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                extensions,
                EVIDENCE);
        List<InventorySlotSnapshot> slots = new ArrayList<>(List.of(
                new InventorySlotSnapshot(
                        0, new ItemStackSnapshot("minecraft:iron_ingot", 4, "Iron Ingot"))));
        InventorySnapshot inventory = new InventorySnapshot(
                slots, 1, 0, 0, ItemStackSnapshot.empty(), true, EVIDENCE);

        slots.clear();
        extension.addProperty("mutated", true);
        extensions.clear();

        assertEquals(9, recipe.ingredients().getFirst().count());
        assertEquals(1, inventory.totalSlots());
        assertEquals(1, inventory.slots().size());
        assertTrue(inventory.complete());
        assertEquals(1, recipe.extensions().size());
        assertTrue(!recipe.extensions().get("create:processing").has("mutated"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> recipe.ingredients().clear());
    }

    @Test
    void rejectsInvalidCountsProbabilitiesAndDuplicateRequirementKeys() {
        IngredientAlternativeSnapshot iron = new IngredientAlternativeSnapshot(
                "item", "minecraft:iron_ingot", List.of("minecraft:iron_ingot"));
        assertThrows(IllegalArgumentException.class, () ->
                new IngredientRequirementSnapshot("input", 0, true, List.of(iron)));
        assertThrows(IllegalArgumentException.class, () -> new RecipeOutputSnapshot(
                new ItemStackSnapshot("minecraft:iron_ingot", 1, "Iron Ingot"), 1.1D));
        IngredientRequirementSnapshot first =
                new IngredientRequirementSnapshot("same", 1, true, List.of(iron));
        assertThrows(IllegalArgumentException.class, () -> recipe(List.of(first, first)));
    }

    private static RecipeEntrySnapshot recipe(List<IngredientRequirementSnapshot> ingredients) {
        return new RecipeEntrySnapshot(
                new RecipeReference("minecraft:recipe_manager", "example:test"),
                "example:test",
                "minecraft:crafting",
                RecipeLayoutSnapshot.unknown(),
                null,
                ingredients,
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(
                        new ItemStackSnapshot("example:test", 1, "Test"), 1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.of(),
                EVIDENCE);
    }
}
