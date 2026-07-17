package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import org.junit.jupiter.api.Test;

final class CalculateCraftabilityToolTest {
    @Test
    void calculatesAgainstCapturedPlayerInventoryWithEvidence() {
        ToolResult.Success<CalculateCraftabilityTool.Output> success = assertInstanceOf(
                ToolResult.Success.class,
                new CalculateCraftabilityTool().invoke(
                        GroundedTestFixtures.fullContext(),
                        new CalculateCraftabilityTool.Input(
                                "minecraft:recipe_manager", "minecraft:iron_block", 1)));

        assertFalse(success.value().result().craftable());
        assertEquals(5L, success.value().result().missing().getFirst().missing());
        assertEquals(2, success.value().evidence().size());
    }

    @Test
    void validatesCountsAndRecipeReferences() {
        CalculateCraftabilityTool tool = new CalculateCraftabilityTool();
        ToolResult.Failure<CalculateCraftabilityTool.Output> invalid = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(
                        GroundedTestFixtures.fullContext(),
                        new CalculateCraftabilityTool.Input(
                                "minecraft:recipe_manager", "minecraft:iron_block", 0)));
        assertEquals("invalid_arguments", invalid.code());

        ToolResult.Failure<CalculateCraftabilityTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(
                        GroundedTestFixtures.fullContext(),
                        new CalculateCraftabilityTool.Input(
                                "minecraft:recipe_manager", "minecraft:missing", 1)));
        assertEquals("recipe_not_found", missing.code());
    }
}
