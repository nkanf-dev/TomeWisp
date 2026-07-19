package dev.openallay.tool.builtin;

import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.agent.tool.ToolAtLeastOne;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolPattern;
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
import java.util.ArrayList;

public final class SearchRecipesTool
        implements Tool<SearchRecipesTool.Input, SearchRecipesTool.Output> {
    @ToolDescription("Search captured recipes using one exact query or a batch of independent exact queries")
    @ToolAtLeastOne({"recipeId", "outputItem", "inputItem", "recipeType", "queries"})
    public record Input(
            @ToolDescription("Exact recipe ID; resolve natural names before using this field")
            @ToolPattern("^[a-z0-9_.-]+:[a-z0-9_./-]+$") @ToolOptional String recipeId,
            @ToolDescription("Exact output item ID returned by resource resolution")
            @ToolPattern("^[a-z0-9_.-]+:[a-z0-9_./-]+$") @ToolOptional String outputItem,
            @ToolDescription("Exact ingredient item ID returned by resource resolution")
            @ToolPattern("^[a-z0-9_.-]+:[a-z0-9_./-]+$") @ToolOptional String inputItem,
            @ToolDescription("Exact recipe type ID")
            @ToolPattern("^[a-z0-9_.-]+:[a-z0-9_./-]+$") @ToolOptional String recipeType,
            @ToolDescription("Batch of independent exact recipe searches; prefer this over repeated calls")
                    @ToolOptional List<Query> queries) {
        public Input(String recipeId, String outputItem, String inputItem, String recipeType) {
            this(recipeId, outputItem, inputItem, recipeType, null);
        }
    }

    @ToolDescription("One exact recipe search inside a batch")
    @ToolAtLeastOne({"recipeId", "outputItem", "inputItem", "recipeType"})
    public record Query(
            @ToolDescription("Exact recipe ID") @ToolOptional String recipeId,
            @ToolDescription("Exact output item ID") @ToolOptional String outputItem,
            @ToolDescription("Exact ingredient item ID") @ToolOptional String inputItem,
            @ToolDescription("Exact recipe type ID") @ToolOptional String recipeType) {}

    public record QueryResult(int index, RecipeCatalog.Query query, List<RecipeCatalog.Summary> recipes) {
        public QueryResult { recipes = List.copyOf(recipes); }
    }

    public record Output(
            RecipeCatalog.Query query,
            List<RecipeCatalog.Summary> recipes,
            List<QueryResult> batches,
            RecipeCatalogStatus catalog,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            recipes = List.copyOf(recipes);
            batches = List.copyOf(batches);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:search_recipes",
            "Search captured recipes by exact id/output/input/type. Put multiple independent targets in queries so they complete in one Tool call.",
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
                || !validOptionalId(input.recipeType())
                || input.queries() != null && input.queries().stream().anyMatch(query -> !valid(query))) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "recipe search criteria must be namespaced identifiers");
        }
        RecipeCatalog.Query query = new RecipeCatalog.Query(
                input.recipeId(), input.outputItem(), input.inputItem(), input.recipeType());
        boolean hasBatch = input.queries() != null && !input.queries().isEmpty();
        if (query.isEmpty() && !hasBatch) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "at least one recipe search criterion is required");
        }
        if (context.recipes().isEmpty()) {
            return new ToolResult.Failure<>(
                    "capability_unavailable", "recipe context was not captured for this invocation");
        }
        RecipeSnapshot snapshot = context.recipes().orElseThrow();
        RecipeCatalog catalog = new RecipeCatalog(snapshot);
        if (hasBatch) {
            List<QueryResult> batches = new ArrayList<>();
            List<RecipeCatalog.Summary> flattened = new ArrayList<>();
            for (int index = 0; index < input.queries().size(); index++) {
                Query item = input.queries().get(index);
                RecipeCatalog.Query itemQuery = new RecipeCatalog.Query(
                        item.recipeId(), item.outputItem(), item.inputItem(), item.recipeType());
                if (itemQuery.isEmpty()) {
                    return new ToolResult.Failure<>(
                            "invalid_arguments", "batch recipe search at index " + index + " is empty");
                }
                List<RecipeCatalog.Summary> matches = catalog.search(itemQuery);
                batches.add(new QueryResult(index, itemQuery, matches));
                flattened.addAll(matches);
            }
            return new ToolResult.Success<>(new Output(
                    query.isEmpty() ? null : query,
                    List.copyOf(flattened),
                    batches,
                    RecipeCatalogStatus.from(snapshot),
                    List.of(snapshot.evidence())));
        }
        return new ToolResult.Success<>(new Output(
                query,
                catalog.search(query),
                List.of(),
                RecipeCatalogStatus.from(snapshot),
                List.of(snapshot.evidence())));
    }

    private static boolean valid(Query query) {
        return query != null
                && validOptionalId(query.recipeId())
                && validOptionalId(query.outputItem())
                && validOptionalId(query.inputItem())
                && validOptionalId(query.recipeType());
    }

    private static boolean validOptionalId(String value) {
        return value == null || BuiltinToolValidation.isIdentifier(value);
    }
}
