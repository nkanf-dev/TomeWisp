package dev.openallay.context;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import dev.openallay.recipe.RecipeUnlockState;

public record RecipeEntrySnapshot(
        RecipeReference reference,
        String id,
        String type,
        RecipeLayoutSnapshot layout,
        String workstation,
        List<IngredientRequirementSnapshot> ingredients,
        List<IngredientRequirementSnapshot> catalysts,
        List<FluidRequirementSnapshot> fluids,
        List<RecipeOutputSnapshot> outputs,
        List<RecipeOutputSnapshot> byproducts,
        RecipeProcessingSnapshot processing,
        List<String> conditions,
        Map<String, JsonObject> extensions,
        RecipeUnlockState unlockState,
        EvidenceMetadata evidence) {
    public RecipeEntrySnapshot {
        Objects.requireNonNull(reference, "reference");
        id = ContextValidation.identifier(id, "id");
        type = ContextValidation.identifier(type, "type");
        Objects.requireNonNull(layout, "layout");
        if (workstation != null) {
            workstation = ContextValidation.identifier(workstation, "workstation");
        }
        ingredients = copyRequirements(ingredients, "ingredients");
        catalysts = copyRequirements(catalysts, "catalysts");
        fluids = List.copyOf(fluids);
        outputs = List.copyOf(outputs);
        byproducts = List.copyOf(byproducts);
        Objects.requireNonNull(processing, "processing");
        conditions = List.copyOf(conditions);
        conditions.forEach(value -> ContextValidation.nonBlank(value, "condition"));
        extensions = immutableExtensions(extensions);
        Objects.requireNonNull(unlockState, "unlockState");
        Objects.requireNonNull(evidence, "evidence");
    }

    public RecipeEntrySnapshot(
            RecipeReference reference,
            String id,
            String type,
            RecipeLayoutSnapshot layout,
            String workstation,
            List<IngredientRequirementSnapshot> ingredients,
            List<IngredientRequirementSnapshot> catalysts,
            List<FluidRequirementSnapshot> fluids,
            List<RecipeOutputSnapshot> outputs,
            List<RecipeOutputSnapshot> byproducts,
            RecipeProcessingSnapshot processing,
            List<String> conditions,
            Map<String, JsonObject> extensions,
            EvidenceMetadata evidence) {
        this(
                reference,
                id,
                type,
                layout,
                workstation,
                ingredients,
                catalysts,
                fluids,
                outputs,
                byproducts,
                processing,
                conditions,
                extensions,
                RecipeUnlockState.UNKNOWN,
                evidence);
    }

    @Override
    public Map<String, JsonObject> extensions() {
        TreeMap<String, JsonObject> copy = new TreeMap<>();
        extensions.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    public RecipeEntrySnapshot withReference(RecipeReference replacement) {
        return new RecipeEntrySnapshot(
                replacement,
                id,
                type,
                layout,
                workstation,
                ingredients,
                catalysts,
                fluids,
                outputs,
                byproducts,
                processing,
                conditions,
                extensions,
                unlockState,
                evidence);
    }

    private static List<IngredientRequirementSnapshot> copyRequirements(
            List<IngredientRequirementSnapshot> values, String name) {
        List<IngredientRequirementSnapshot> copy = List.copyOf(values);
        HashSet<String> keys = new HashSet<>();
        for (IngredientRequirementSnapshot value : copy) {
            if (!keys.add(value.key())) {
                throw new IllegalArgumentException(name + " contain duplicate requirement key " + value.key());
            }
        }
        return copy;
    }

    private static Map<String, JsonObject> immutableExtensions(Map<String, JsonObject> values) {
        TreeMap<String, JsonObject> copy = new TreeMap<>();
        Objects.requireNonNull(values, "extensions").forEach((key, value) -> copy.put(
                ContextValidation.identifier(key, "extension key"),
                Objects.requireNonNull(value, "extension value").deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
}
