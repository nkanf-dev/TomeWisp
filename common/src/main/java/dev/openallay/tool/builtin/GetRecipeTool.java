package dev.openallay.tool.builtin;

import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.context.RecipeSnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.recipe.RecipeCatalog;
import dev.openallay.recipe.RecipeCatalogStatus;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Set;

public final class GetRecipeTool implements Tool<GetRecipeTool.Input, GetRecipeTool.Output> {
    public record Input(String sourceId, String generation, String recipeId) {}

    public record Output(
            RecipeEntrySnapshot recipe,
            RecipeCatalogStatus catalog,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:get_recipe",
            "Get complete captured details for one recipe reference",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.RECIPES));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || !BuiltinToolValidation.isIdentifier(input.sourceId())
                || !validGeneration(input.generation())
                || !BuiltinToolValidation.isIdentifier(input.recipeId())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "sourceId, generation, and recipeId are required");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "recipe context was not captured for this invocation");
        }
        RecipeSnapshot snapshot = context.recipes().orElseThrow();
        return new RecipeCatalog(snapshot)
                .get(new RecipeReference(input.sourceId(), input.generation(), input.recipeId()))
                .<ToolResult<Output>>map(recipe -> new ToolResult.Success<>(new Output(
                        recipe,
                        RecipeCatalogStatus.from(snapshot),
                        List.of(snapshot.evidence(), recipe.evidence()))))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "stale_reference", "the requested recipe reference is stale"));
    }

    private static boolean validGeneration(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
