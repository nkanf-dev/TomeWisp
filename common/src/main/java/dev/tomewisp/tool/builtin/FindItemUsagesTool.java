package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.recipe.RecipeCatalog;
import dev.tomewisp.recipe.RecipeCatalogStatus;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;

public final class FindItemUsagesTool
        implements Tool<FindItemUsagesTool.Input, FindItemUsagesTool.Output> {
    public record Input(String itemId) {}

    public record Output(
            String itemId,
            List<RecipeCatalog.Usage> usages,
            RecipeCatalogStatus catalog,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            usages = List.copyOf(usages);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:find_item_usages",
            "Find captured recipes that use or produce an item, classified by role",
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
        if (input == null || !BuiltinToolValidation.isIdentifier(input.itemId())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "itemId must be a namespaced Minecraft identifier");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "recipe context was not captured for this invocation");
        }
        RecipeSnapshot snapshot = context.recipes().orElseThrow();
        return new ToolResult.Success<>(new Output(
                input.itemId(),
                new RecipeCatalog(snapshot).usages(input.itemId()),
                RecipeCatalogStatus.from(snapshot),
                List.of(snapshot.evidence())));
    }
}
