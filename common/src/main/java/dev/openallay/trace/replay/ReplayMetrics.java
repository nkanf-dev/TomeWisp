package dev.openallay.trace.replay;

public record ReplayMetrics(
        long registryEntries,
        long recipes,
        long inventorySlots,
        long contextEstimatedSerializedBytes,
        long contextCaptureNanos,
        long toolResultSerializedBytes,
        long totalDurationNanos) {
    public ReplayMetrics {
        if (registryEntries < 0
                || recipes < 0
                || inventorySlots < 0
                || contextEstimatedSerializedBytes < 0
                || contextCaptureNanos < 0
                || toolResultSerializedBytes < 0
                || totalDurationNanos < 0) {
            throw new IllegalArgumentException("Replay metrics must be non-negative");
        }
    }
}
