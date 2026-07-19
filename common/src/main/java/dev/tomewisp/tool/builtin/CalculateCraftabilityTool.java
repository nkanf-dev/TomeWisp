package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.crafting.CraftabilityCalculator;
import dev.tomewisp.crafting.CraftabilityResult;
import dev.tomewisp.recipe.RecipeCatalog;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;

public final class CalculateCraftabilityTool implements Tool<
        CalculateCraftabilityTool.Input, CalculateCraftabilityTool.Output> {
    public record Input(String sourceId, String generation, String recipeId, long crafts) {}

    public record Output(
            RecipeReference recipe,
            CraftabilityResult result,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:calculate_craftability",
            "Allocate captured inventory against one recipe without recursively crafting intermediates",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.PLAYER, ContextCapability.RECIPES));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null
                || !BuiltinToolValidation.isIdentifier(input.sourceId())
                || input.generation() == null
                || !input.generation().matches("[0-9a-f]{64}")
                || !BuiltinToolValidation.isIdentifier(input.recipeId())
                || input.crafts() <= 0) {
            return new ToolResult.Failure<>(
                    "invalid_arguments",
                    "valid sourceId, generation, recipeId, and positive crafts are required");
        }
        if (context.player().isEmpty() || context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "player and recipe context are required");
        }
        RecipeReference reference = new RecipeReference(
                input.sourceId(), input.generation(), input.recipeId());
        var recipe = new RecipeCatalog(context.recipes().orElseThrow()).get(reference);
        if (recipe.isEmpty()) {
            return new ToolResult.Failure<>(
                    "stale_reference", "the requested recipe reference is stale");
        }
        try {
            var inventory = context.player().orElseThrow().inventory();
            return new ToolResult.Success<>(new Output(
                    reference,
                    new CraftabilityCalculator().calculate(
                            recipe.orElseThrow(), inventory, input.crafts()),
                    List.of(recipe.orElseThrow().evidence(), inventory.evidence())));
        } catch (ArithmeticException | IllegalArgumentException failure) {
            return new ToolResult.Failure<>("invalid_arguments", failure.getMessage());
        }
    }
}
