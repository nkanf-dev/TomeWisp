package dev.openallay.context;

public record ContextMetrics(
        long registryEntries,
        long recipes,
        long inventorySlots,
        long estimatedSerializedBytes,
        long captureNanos) {
    public ContextMetrics {
        ContextValidation.nonNegative(registryEntries, "registryEntries");
        ContextValidation.nonNegative(recipes, "recipes");
        ContextValidation.nonNegative(inventorySlots, "inventorySlots");
        ContextValidation.nonNegative(estimatedSerializedBytes, "estimatedSerializedBytes");
        ContextValidation.nonNegative(captureNanos, "captureNanos");
    }
}
