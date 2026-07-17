package dev.tomewisp.crafting;

public record IngredientAllocation(String requirementKey, String itemId, long count) {
    public IngredientAllocation {
        if (requirementKey == null || requirementKey.isBlank()
                || itemId == null || itemId.isBlank()
                || count <= 0) {
            throw new IllegalArgumentException("allocation fields and positive count are required");
        }
    }
}
