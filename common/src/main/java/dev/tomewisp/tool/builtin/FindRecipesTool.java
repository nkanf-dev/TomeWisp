package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;

public final class FindRecipesTool
        implements Tool<FindRecipesTool.Input, FindRecipesTool.Output> {
    public record Input(String outputItem) {}

    public record Output(String outputItem, List<RecipeEntrySnapshot> recipes) {
        public Output {
            recipes = List.copyOf(recipes);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:find_recipes",
            "Find every captured recipe that produces the requested item",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

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

        List<RecipeEntrySnapshot> recipes = context.recipes().orElseThrow().recipes().stream()
                .filter(recipe -> recipe.outputs().stream()
                        .anyMatch(output -> output.itemId().equals(input.outputItem())))
                .toList();
        return new ToolResult.Success<>(new Output(input.outputItem(), recipes));
    }
}
