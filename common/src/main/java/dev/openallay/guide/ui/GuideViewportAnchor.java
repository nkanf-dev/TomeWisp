package dev.openallay.guide.ui;

/** Stable row and pixel offset used to preserve reading position across page merges. */
public record GuideViewportAnchor(String rowId, int pixelOffset) {
    public GuideViewportAnchor {
        if (rowId == null || rowId.isBlank()) {
            throw new IllegalArgumentException("viewport anchor row is required");
        }
        if (pixelOffset < 0) {
            throw new IllegalArgumentException("viewport anchor offset must not be negative");
        }
    }
}
