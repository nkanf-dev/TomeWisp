package dev.tomewisp.crafting;

import java.util.List;

public record CraftabilityResult(
        boolean craftable,
        boolean conclusive,
        long requestedCrafts,
        long maximumCrafts,
        List<IngredientAllocation> allocations,
        List<MissingRequirement> missing) {
    public CraftabilityResult {
        if (requestedCrafts <= 0 || maximumCrafts < 0) {
            throw new IllegalArgumentException("craft counts are invalid");
        }
        allocations = List.copyOf(allocations);
        missing = List.copyOf(missing);
        if (craftable != missing.isEmpty()) {
            throw new IllegalArgumentException("craftable must agree with missing requirements");
        }
    }
}
