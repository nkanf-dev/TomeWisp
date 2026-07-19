package dev.openallay.context;

public record RecipeProcessingSnapshot(
        Long durationTicks, Long energy, Double temperature) {
    public RecipeProcessingSnapshot {
        if (durationTicks != null && durationTicks < 0) {
            throw new IllegalArgumentException("durationTicks must not be negative");
        }
        if (energy != null && energy < 0) {
            throw new IllegalArgumentException("energy must not be negative");
        }
        if (temperature != null && !Double.isFinite(temperature)) {
            throw new IllegalArgumentException("temperature must be finite");
        }
    }

    public static RecipeProcessingSnapshot unknown() {
        return new RecipeProcessingSnapshot(null, null, null);
    }
}
