package dev.openallay.context;

public record ItemStackSnapshot(String itemId, int count, String displayName) {
    public ItemStackSnapshot {
        itemId = ContextValidation.identifier(itemId, "itemId");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        displayName = ContextValidation.nonBlank(displayName, "displayName");
    }

    public static ItemStackSnapshot empty() {
        return new ItemStackSnapshot("minecraft:air", 0, "Air");
    }
}
