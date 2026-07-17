package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.recipe.RecipeCatalog;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;

public final class GetRecipeTool implements Tool<GetRecipeTool.Input, GetRecipeTool.Output> {
    public record Input(String sourceId, String recipeId) {}

    public record Output(RecipeEntrySnapshot recipe, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:get_recipe",
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
                || !BuiltinToolValidation.isIdentifier(input.recipeId())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "sourceId and recipeId must be namespaced identifiers");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "recipe context was not captured for this invocation");
        }
        RecipeSnapshot snapshot = context.recipes().orElseThrow();
        return new RecipeCatalog(snapshot)
                .get(new RecipeReference(input.sourceId(), input.recipeId()))
                .<ToolResult<Output>>map(recipe -> new ToolResult.Success<>(new Output(
                        recipe, List.of(snapshot.evidence(), recipe.evidence()))))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "recipe_not_found", "the requested recipe reference is not visible"));
    }
}
