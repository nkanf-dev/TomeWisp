package dev.openallay.resource.mount;

import dev.openallay.context.IngredientAlternativeSnapshot;
import dev.openallay.context.IngredientRequirementSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeOutputSnapshot;
import dev.openallay.context.RecipeSnapshot;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class RecipeResourceMount implements ResourceMount {
    private final Supplier<RecipeSnapshot> source;
    private long generation;

    public RecipeResourceMount(Supplier<RecipeSnapshot> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("recipe");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        RecipeSnapshot snapshot = Objects.requireNonNull(source.get(), "recipe snapshot");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), snapshot.evidence());
        snapshot.recipes().forEach(recipe -> add(tree, recipe));
        return new ResourceSnapshot(root(), "recipes-" + ++generation,
                snapshot.evidence().capturedAt(), tree.build());
    }

    private void add(ResourceTreeBuilder tree, RecipeEntrySnapshot recipe) {
        String namespace = recipe.id().substring(0, recipe.id().indexOf(':'));
        String valuePath = recipe.id().substring(recipe.id().indexOf(':') + 1);
        ResourcePath path = ResourcePath.of("recipe", namespace, valuePath);
        LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
        fields.put("id", new ResourceValue.Scalar(recipe.id()));
        fields.put("type", new ResourceValue.Scalar(recipe.type()));
        fields.put("source", new ResourceValue.Scalar(recipe.reference().sourceId()));
        fields.put("source_generation", new ResourceValue.Scalar(recipe.reference().generation()));
        fields.put("workstation", new ResourceValue.Scalar(recipe.workstation()));
        fields.put("layout", ResourceValues.record(Map.of(
                "width", recipe.layout().width(),
                "height", recipe.layout().height(),
                "shaped", recipe.layout().shaped())));
        fields.put("ingredients", requirements(recipe.ingredients()));
        fields.put("catalysts", requirements(recipe.catalysts()));
        fields.put("outputs", outputs(recipe.outputs()));
        fields.put("byproducts", outputs(recipe.byproducts()));
        LinkedHashMap<String, ResourceValue> processing = new LinkedHashMap<>();
        processing.put("duration_ticks", ResourceValues.scalarOrValue(recipe.processing().durationTicks()));
        processing.put("energy", ResourceValues.scalarOrValue(recipe.processing().energy()));
        processing.put("temperature", ResourceValues.scalarOrValue(recipe.processing().temperature()));
        fields.put("processing", new ResourceValue.RecordValue(processing));
        fields.put("conditions", ResourceValues.strings(recipe.conditions()));
        fields.put("unlock_state", new ResourceValue.Scalar(recipe.unlockState().name().toLowerCase()));

        ArrayList<ResourceLink> links = new ArrayList<>();
        for (IngredientRequirementSnapshot requirement : recipe.ingredients()) {
            for (IngredientAlternativeSnapshot alternative : requirement.alternatives()) {
                for (String itemId : alternative.resolvedItems()) {
                    links.add(new ResourceLink("ingredient", itemPath(itemId), itemId));
                }
                if (alternative.kind().equals("item")) {
                    links.add(new ResourceLink("ingredient", itemPath(alternative.id()), alternative.id()));
                }
            }
        }
        for (RecipeOutputSnapshot output : recipe.outputs()) {
            links.add(new ResourceLink("output", itemPath(output.stack().itemId()), output.stack().displayName()));
        }
        if (recipe.workstation() != null) {
            links.add(new ResourceLink("workstation", itemPath(recipe.workstation()), recipe.workstation()));
        }

        tree.put(path, ResourceKind.RECORD, new ResourceValue.RecordValue(fields), links, recipe.evidence(),
                new ResourcePresentation(ResourcePresentation.Kind.RECIPE,
                        Map.of("recipeId", recipe.id(), "sourceId", recipe.reference().sourceId())));
    }

    private static ResourceValue requirements(List<IngredientRequirementSnapshot> requirements) {
        return new ResourceValue.ListValue(requirements.stream().map(requirement -> {
            List<ResourceValue> alternatives = requirement.alternatives().stream().map(alternative ->
                    ResourceValues.record(Map.of(
                            "kind", alternative.kind(),
                            "id", alternative.id(),
                            "resolved_items", ResourceValues.strings(alternative.resolvedItems())))).toList();
            return ResourceValues.record(Map.of(
                    "key", requirement.key(),
                    "count", requirement.count(),
                    "consumed", requirement.consumed(),
                    "alternatives", new ResourceValue.ListValue(alternatives)));
        }).toList());
    }

    private static ResourceValue outputs(List<RecipeOutputSnapshot> outputs) {
        return new ResourceValue.ListValue(outputs.stream().map(output -> {
            ItemStackSnapshot stack = output.stack();
            return ResourceValues.record(Map.of(
                    "item", stack.itemId(),
                    "name", stack.displayName(),
                    "count", stack.count(),
                    "probability", output.probability()));
        }).toList());
    }

    private static ResourcePath itemPath(String itemId) {
        int separator = itemId.indexOf(':');
        return ResourcePath.of("item", itemId.substring(0, separator), itemId.substring(separator + 1));
    }
}
