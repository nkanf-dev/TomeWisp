package dev.tomewisp.guide.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure variable-height row index with binary-search visibility and stable anchors. */
public final class GuideTranscriptVirtualizer {
    public record Row(String id, int height) {
        public Row {
            if (id == null || id.isBlank() || height <= 0) {
                throw new IllegalArgumentException("virtual row identity and height are required");
            }
        }
    }

    public record Window(int fromIndex, int toIndexExclusive, int totalHeight) {
        public Window {
            if (fromIndex < 0 || toIndexExclusive < fromIndex || totalHeight < 0) {
                throw new IllegalArgumentException("invalid virtual window");
            }
        }
    }

    private List<Row> rows = List.of();
    private int[] offsets = {0};
    private Map<String, Integer> indexes = Map.of();

    public void update(List<Row> replacement) {
        replacement = List.copyOf(replacement);
        LinkedHashMap<String, Integer> nextIndexes = new LinkedHashMap<>();
        int[] nextOffsets = new int[replacement.size() + 1];
        for (int index = 0; index < replacement.size(); index++) {
            Row row = replacement.get(index);
            if (nextIndexes.put(row.id(), index) != null) {
                throw new IllegalArgumentException("duplicate virtual row ID " + row.id());
            }
            nextOffsets[index + 1] = Math.addExact(nextOffsets[index], row.height());
        }
        rows = replacement;
        offsets = nextOffsets;
        indexes = java.util.Collections.unmodifiableMap(nextIndexes);
    }

    public Window visible(int scroll, int viewportHeight, int overscanPixels) {
        if (scroll < 0 || viewportHeight < 0 || overscanPixels < 0) {
            throw new IllegalArgumentException("viewport dimensions must not be negative");
        }
        if (rows.isEmpty()) return new Window(0, 0, 0);
        int top = Math.max(0, scroll - overscanPixels);
        int bottom = Math.min(totalHeight(), Math.addExact(scroll, viewportHeight + overscanPixels));
        int from = rowAt(top);
        int to = bottom >= totalHeight() ? rows.size() : Math.min(rows.size(), rowAt(bottom) + 1);
        return new Window(from, to, totalHeight());
    }

    public GuideViewportAnchor anchorAt(int scroll) {
        if (rows.isEmpty()) return null;
        int clamped = Math.max(0, Math.min(scroll, Math.max(0, totalHeight() - 1)));
        int index = rowAt(clamped);
        return new GuideViewportAnchor(rows.get(index).id(), clamped - offsets[index]);
    }

    public int restore(GuideViewportAnchor anchor, int fallbackScroll, int viewportHeight) {
        if (anchor == null || !indexes.containsKey(anchor.rowId())) {
            return clampScroll(fallbackScroll, viewportHeight);
        }
        int index = indexes.get(anchor.rowId());
        return clampScroll(offsets[index] + anchor.pixelOffset(), viewportHeight);
    }

    public boolean atBottom(int scroll, int viewportHeight) {
        return scroll >= maximumScroll(viewportHeight) - 1;
    }

    public int maximumScroll(int viewportHeight) {
        return Math.max(0, totalHeight() - Math.max(0, viewportHeight));
    }

    public int clampScroll(int scroll, int viewportHeight) {
        return Math.max(0, Math.min(scroll, maximumScroll(viewportHeight)));
    }

    public int offset(int rowIndex) {
        if (rowIndex < 0 || rowIndex > rows.size()) throw new IndexOutOfBoundsException(rowIndex);
        return offsets[rowIndex];
    }

    public int totalHeight() {
        return offsets[offsets.length - 1];
    }

    public List<Row> rows() {
        return rows;
    }

    private int rowAt(int pixel) {
        int low = 0;
        int high = rows.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (pixel < offsets[middle]) {
                high = middle - 1;
            } else if (pixel >= offsets[middle + 1]) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return Math.max(0, Math.min(rows.size() - 1, low));
    }
}
