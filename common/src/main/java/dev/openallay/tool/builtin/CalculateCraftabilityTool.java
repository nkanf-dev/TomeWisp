package dev.openallay.tool.builtin;

import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RecipeReference;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.crafting.CraftabilityCalculator;
import dev.openallay.crafting.CraftabilityResult;
import dev.openallay.recipe.RecipeCatalog;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.ModelFacingToolOutput;
import java.util.List;
import java.util.Set;

public final class CalculateCraftabilityTool implements Tool<
        CalculateCraftabilityTool.Input, CalculateCraftabilityTool.Output> {
    public record Input(String sourceId, String generation, String recipeId, long crafts) {}

    public record Output(
            RecipeReference recipe,
            CraftabilityResult result,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing, ModelFacingToolOutput {
        public Output {
            evidence = List.copyOf(evidence);
        }

        @Override
        public String modelText() {
            StringBuilder text = new StringBuilder()
                    .append("recipe: ").append(recipe.recipeId()).append('\n')
                    .append("craftable: ").append(result.craftable()).append('\n')
                    .append("conclusive: ").append(result.conclusive()).append('\n')
                    .append("requestedCrafts: ").append(result.requestedCrafts()).append('\n')
                    .append("maximumCrafts: ").append(result.maximumCrafts()).append('\n')
                    .append("allocation:\n");
            int allocationCount = Math.min(12, result.allocations().size());
            for (int index = 0; index < allocationCount; index++) {
                var allocation = result.allocations().get(index);
                text.append("- ")
                        .append(allocation.requirementKey())
                        .append(" <- ")
                        .append(allocation.itemId())
                        .append(" x")
                        .append(allocation.count())
                        .append('\n');
            }
            if (result.allocations().size() > allocationCount) {
                text.append("- ")
                        .append(result.allocations().size() - allocationCount)
                        .append(" more allocation row(s)\n");
            }
            text.append("missing:\n");
            int missingCount = Math.min(12, result.missing().size());
            for (int index = 0; index < missingCount; index++) {
                var missing = result.missing().get(index);
                text.append("- ")
                        .append(missing.requirementKey())
                        .append(": ")
                        .append(missing.missing())
                        .append(" missing of ")
                        .append(missing.required())
                        .append('\n');
            }
            if (result.missing().size() > missingCount) {
                text.append("- ")
                        .append(result.missing().size() - missingCount)
                        .append(" more missing row(s)\n");
            }
            return text.toString().stripTrailing();
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:calculate_craftability",
            "ONLY answer an explicit question about whether the player's currently captured "
                    + "inventory can craft one exact recipe. Never use this Tool for recipe ranking, "
                    + "minimum material or ingredient comparisons, recipe inspection, or verification "
                    + "of a completed JavaScript result. Allocation is non-recursive.",
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
