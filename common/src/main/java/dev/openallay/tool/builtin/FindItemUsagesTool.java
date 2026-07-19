package dev.openallay.tool.builtin;

import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
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
            "openallay:find_item_usages",
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
