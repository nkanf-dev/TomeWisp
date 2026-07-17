package dev.tomewisp.recipe;

import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.IngredientRequirementSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeOutputSnapshot;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.RecipeSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, deterministic query projection over one captured recipe snapshot. */
public final class RecipeCatalog {
    public record Query(
            String recipeId, String outputItem, String inputItem, String recipeType) {
        public Query {
            recipeId = normalize(recipeId);
            outputItem = normalize(outputItem);
            inputItem = normalize(inputItem);
            recipeType = normalize(recipeType);
        }

        public boolean isEmpty() {
            return recipeId == null && outputItem == null && inputItem == null && recipeType == null;
        }
    }

    public record Summary(
            RecipeReference reference,
            String id,
            String type,
            List<RecipeOutputSnapshot> outputs,
            String workstation,
            EvidenceMetadata evidence) {
        public Summary {
            Objects.requireNonNull(reference, "reference");
            outputs = List.copyOf(outputs);
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    public enum UsageRole {
        INPUT,
        CATALYST,
        OUTPUT,
        BYPRODUCT
    }

    public record Usage(
            RecipeReference reference, UsageRole role, EvidenceMetadata evidence) {
        public Usage {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    private static final Comparator<RecipeEntrySnapshot> RECIPE_ORDER = Comparator
            .comparing((RecipeEntrySnapshot value) -> value.reference().sourceId())
            .thenComparing(value -> value.reference().recipeId());
    private static final Comparator<Usage> USAGE_ORDER = Comparator
            .comparing((Usage value) -> value.reference().sourceId())
            .thenComparing(value -> value.reference().recipeId())
            .thenComparing(Usage::role);

    private final List<RecipeEntrySnapshot> recipes;

    public RecipeCatalog(RecipeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        recipes = snapshot.recipes().stream().sorted(RECIPE_ORDER).toList();
    }

    public List<Summary> search(Query query) {
        Objects.requireNonNull(query, "query");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("at least one recipe search criterion is required");
        }
        return recipes.stream()
                .filter(recipe -> query.recipeId() == null
                        || recipe.id().equals(query.recipeId())
                        || recipe.reference().recipeId().equals(query.recipeId()))
                .filter(recipe -> query.recipeType() == null || recipe.type().equals(query.recipeType()))
                .filter(recipe -> query.outputItem() == null
                        || matchesOutputs(recipe.outputs(), query.outputItem())
                        || matchesOutputs(recipe.byproducts(), query.outputItem()))
                .filter(recipe -> query.inputItem() == null
                        || matchesRequirements(recipe.ingredients(), query.inputItem())
                        || matchesRequirements(recipe.catalysts(), query.inputItem()))
                .map(RecipeCatalog::summary)
                .toList();
    }

    public Optional<RecipeEntrySnapshot> get(RecipeReference reference) {
        Objects.requireNonNull(reference, "reference");
        return recipes.stream().filter(recipe -> recipe.reference().equals(reference)).findFirst();
    }

    public List<Usage> usages(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        List<Usage> result = new ArrayList<>();
        for (RecipeEntrySnapshot recipe : recipes) {
            addUsage(result, recipe, UsageRole.INPUT, matchesRequirements(recipe.ingredients(), itemId));
            addUsage(result, recipe, UsageRole.CATALYST, matchesRequirements(recipe.catalysts(), itemId));
            addUsage(result, recipe, UsageRole.OUTPUT, matchesOutputs(recipe.outputs(), itemId));
            addUsage(result, recipe, UsageRole.BYPRODUCT, matchesOutputs(recipe.byproducts(), itemId));
        }
        return result.stream().sorted(USAGE_ORDER).toList();
    }

    private static Summary summary(RecipeEntrySnapshot recipe) {
        return new Summary(
                recipe.reference(),
                recipe.id(),
                recipe.type(),
                recipe.outputs(),
                recipe.workstation(),
                recipe.evidence());
    }

    private static boolean matchesRequirements(
            List<IngredientRequirementSnapshot> requirements, String itemId) {
        return requirements.stream().flatMap(value -> value.alternatives().stream()).anyMatch(alternative ->
                alternative.id().equals(itemId) || alternative.resolvedItems().contains(itemId));
    }

    private static boolean matchesOutputs(List<RecipeOutputSnapshot> outputs, String itemId) {
        return outputs.stream().anyMatch(output -> output.stack().itemId().equals(itemId));
    }

    private static void addUsage(
            List<Usage> result,
            RecipeEntrySnapshot recipe,
            UsageRole role,
            boolean matches) {
        if (matches) {
            result.add(new Usage(recipe.reference(), role, recipe.evidence()));
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
