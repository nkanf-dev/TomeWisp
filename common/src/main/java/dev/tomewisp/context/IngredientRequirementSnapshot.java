package dev.tomewisp.context;

import java.util.List;

public record IngredientRequirementSnapshot(
        String key,
        long count,
        boolean consumed,
        List<IngredientAlternativeSnapshot> alternatives) {
    public IngredientRequirementSnapshot {
        key = ContextValidation.nonBlank(key, "key");
        if (count <= 0) {
            throw new IllegalArgumentException("ingredient count must be positive");
        }
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("ingredient alternatives must not be empty");
        }
    }
}
