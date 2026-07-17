package dev.tomewisp.tool.builtin;

import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.recipe.RecipeCatalog;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;

public final class SearchRecipesTool
        implements Tool<SearchRecipesTool.Input, SearchRecipesTool.Output> {
    public record Input(
            @ToolOptional String recipeId,
            @ToolOptional String outputItem,
            @ToolOptional String inputItem,
            @ToolOptional String recipeType) {}

    public record Output(
            RecipeCatalog.Query query,
            List<RecipeCatalog.Summary> recipes,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            recipes = List.copyOf(recipes);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:search_recipes",
            "Search captured recipes by id, output, input, or recipe type",
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
        if (input == null || !validOptionalId(input.recipeId())
                || !validOptionalId(input.outputItem())
                || !validOptionalId(input.inputItem())
                || !validOptionalId(input.recipeType())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "recipe search criteria must be namespaced identifiers");
        }
        RecipeCatalog.Query query = new RecipeCatalog.Query(
                input.recipeId(), input.outputItem(), input.inputItem(), input.recipeType());
        if (query.isEmpty()) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "at least one recipe search criterion is required");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "recipe context was not captured for this invocation");
        }
        RecipeSnapshot snapshot = context.recipes().orElseThrow();
        return new ToolResult.Success<>(new Output(
                query, new RecipeCatalog(snapshot).search(query), List.of(snapshot.evidence())));
    }

    private static boolean validOptionalId(String value) {
        return value == null || value.isBlank() || BuiltinToolValidation.isIdentifier(value);
    }
}
