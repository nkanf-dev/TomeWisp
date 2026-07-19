package dev.openallay.context;

public record FluidRequirementSnapshot(String fluidId, long amount, boolean consumed) {
    public FluidRequirementSnapshot {
        fluidId = ContextValidation.identifier(fluidId, "fluidId");
        if (amount <= 0) {
            throw new IllegalArgumentException("fluid amount must be positive");
        }
    }
}
