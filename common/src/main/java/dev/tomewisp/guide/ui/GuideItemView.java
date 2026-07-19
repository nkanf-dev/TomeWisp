package dev.tomewisp.guide.ui;

/** Immutable item-stack projection safe for player-facing native UI cards. */
public record GuideItemView(String itemId, String displayName, long count) {
    public GuideItemView {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        displayName = displayName == null || displayName.isBlank() ? itemId : displayName;
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
    }
}
