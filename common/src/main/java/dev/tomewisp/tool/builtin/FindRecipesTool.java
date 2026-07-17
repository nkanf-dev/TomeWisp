package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.recipe.RecipeCatalog;

@Deprecated(forRemoval = false)
public final class FindRecipesTool
        implements Tool<FindRecipesTool.Input, FindRecipesTool.Output> {
    public record Input(String outputItem) {}

    public record Output(
            String outputItem,
            List<RecipeEntrySnapshot> recipes,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            recipes = List.copyOf(recipes);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:find_recipes",
            "Find every captured recipe that produces the requested item",
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
        if (input == null || !BuiltinToolValidation.isIdentifier(input.outputItem())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments",
                    "outputItem must be a valid namespaced Minecraft identifier");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "missing_context", "recipe context was not captured for this invocation");
        }

        var snapshot = context.recipes().orElseThrow();
        RecipeCatalog catalog = new RecipeCatalog(snapshot);
        List<RecipeEntrySnapshot> recipes = catalog.search(new RecipeCatalog.Query(
                        null, input.outputItem(), null, null))
                .stream()
                .map(summary -> catalog.get(summary.reference()).orElseThrow())
                .toList();
        return new ToolResult.Success<>(new Output(
                input.outputItem(), recipes, List.of(snapshot.evidence())));
    }
}
