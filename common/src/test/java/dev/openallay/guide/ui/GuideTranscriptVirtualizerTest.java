package dev.openallay.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuideTranscriptVirtualizerTest {
    @Test
    void binarySearchesThousandsOfVariableHeightRows() {
        GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
        ArrayList<GuideTranscriptVirtualizer.Row> rows = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < 10_000; index++) {
            int height = 10 + index % 17;
            rows.add(new GuideTranscriptVirtualizer.Row("row-" + index, height));
            total += height;
        }
        virtualizer.update(rows);

        GuideTranscriptVirtualizer.Window visible = virtualizer.visible(83_000, 240, 40);

        assertEquals(total, visible.totalHeight());
        assertTrue(visible.fromIndex() > 4_000);
        assertTrue(visible.toIndexExclusive() - visible.fromIndex() < 30);
    }

    @Test
    void preservesAnchorWhenEarlierRowsArePrependedAndFollowOwnershipIsExplicit() {
        GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
        virtualizer.update(List.of(
                row("b", 30), row("c", 40), row("d", 50)));
        GuideViewportAnchor anchor = virtualizer.anchorAt(35);
        assertEquals("c", anchor.rowId());
        assertEquals(5, anchor.pixelOffset());
        assertTrue(virtualizer.atBottom(60, 60));
        assertFalse(virtualizer.atBottom(20, 60));

        virtualizer.update(List.of(
                row("a0", 20), row("a1", 25), row("b", 30), row("c", 40), row("d", 50)));

        assertEquals(80, virtualizer.restore(anchor, 35, 60));
        assertEquals(105, virtualizer.maximumScroll(60));
    }

    @Test
    void changingOneRowHeightMovesOnlyLaterOffsets() {
        GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
        virtualizer.update(List.of(row("a", 10), row("b", 20), row("c", 30)));
        int beforeA = virtualizer.offset(0);
        int beforeB = virtualizer.offset(1);
        virtualizer.update(List.of(row("a", 10), row("b", 50), row("c", 30)));

        assertEquals(beforeA, virtualizer.offset(0));
        assertEquals(beforeB, virtualizer.offset(1));
        assertEquals(60, virtualizer.offset(2));
    }

    private static GuideTranscriptVirtualizer.Row row(String id, int height) {
        return new GuideTranscriptVirtualizer.Row(id, height);
    }
}
