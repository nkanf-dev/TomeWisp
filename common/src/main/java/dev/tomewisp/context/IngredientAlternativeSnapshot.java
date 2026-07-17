package dev.tomewisp.context;

import java.util.List;

public record IngredientAlternativeSnapshot(
        String kind, String id, List<String> resolvedItems) {
    public IngredientAlternativeSnapshot {
        kind = ContextValidation.nonBlank(kind, "kind");
        if (!kind.equals("item") && !kind.equals("tag")) {
            throw new IllegalArgumentException("ingredient alternative kind must be item or tag");
        }
        id = ContextValidation.identifier(id, "id");
        resolvedItems = List.copyOf(resolvedItems);
        resolvedItems.forEach(value -> ContextValidation.identifier(value, "resolved item"));
    }
}
