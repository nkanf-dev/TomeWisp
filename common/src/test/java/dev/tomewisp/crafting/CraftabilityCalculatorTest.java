package dev.tomewisp.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CraftabilityCalculatorTest {
    @Test
    void allocatesOverlappingAlternativesGlobally() {
        CraftabilityResult result = new CraftabilityCalculator().calculate(
                GroundedTestFixtures.overlappingAlternativeRecipe(),
                GroundedTestFixtures.inventory(Map.of(
                        "minecraft:oak_planks", 1L,
                        "minecraft:birch_planks", 1L)),
                1);

        assertTrue(result.craftable());
        assertTrue(result.conclusive());
        assertEquals(2L, result.allocations().stream()
                .mapToLong(IngredientAllocation::count)
                .sum());
        assertTrue(result.missing().isEmpty());
        assertEquals(1L, result.maximumCrafts());
    }

    @Test
    void reportsMissingMaterialsForRequestedCrafts() {
        CraftabilityResult result = new CraftabilityCalculator().calculate(
                GroundedTestFixtures.ironBlockRecipe(),
                GroundedTestFixtures.inventory(Map.of("minecraft:iron_ingot", 4L)),
                1);

        assertFalse(result.craftable());
        assertEquals(0L, result.maximumCrafts());
        assertEquals(5L, result.missing().getFirst().missing());
    }

    @Test
    void partialEvidenceNeverClaimsAConclusivePositive() {
        CraftabilityResult result = new CraftabilityCalculator().calculate(
                GroundedTestFixtures.ironBlockRecipe(),
                GroundedTestFixtures.inventory(
                        Map.of("minecraft:iron_ingot", 9L), DataCompleteness.PARTIAL),
                1);

        assertTrue(result.craftable());
        assertFalse(result.conclusive());
    }
}
