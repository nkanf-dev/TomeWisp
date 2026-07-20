package dev.openallay.resource.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.agent.tool.ModelToolResultView;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolGroupBudgetAllocatorTest {
    private final ToolGroupBudgetAllocator allocator = new ToolGroupBudgetAllocator();

    @Test
    void everyParallelResultGetsAReceiptBeforeCompleteLinesAreDistributed() {
        List<ModelToolResultView> views = List.of(view("a", "alpha"), view("b", "beta"));
        int minimum = allocator.minimumTokens(views);

        ToolGroupBudgetAllocator.Allocation allocation = allocator.allocate(views, minimum + 45);

        assertEquals(2, allocation.views().size());
        assertTrue(allocation.views().get(0).text().contains("resource: /result/a"));
        assertTrue(allocation.views().get(1).text().contains("resource: /result/b"));
        assertTrue(allocation.views().get(0).text().contains("authority: client_visible"));
        assertTrue(allocation.views().get(0).text().contains("range: 0..2"));
        assertFalse(allocation.complete().get(0));
        assertFalse(allocation.complete().get(1));
        assertFalse(allocation.views().get(0).text().contains("partial-unit-"));
    }

    @Test
    void fullBudgetPreservesOriginalTextAndTooSmallBudgetFailsExplicitly() {
        List<ModelToolResultView> views = List.of(view("a", "alpha"));
        ToolGroupBudgetAllocator.Allocation complete = allocator.allocate(views, 100_000);

        assertEquals(views.getFirst().text(), complete.views().getFirst().text());
        assertEquals(List.of(true), complete.complete());
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(views, allocator.minimumTokens(views) - 1));
    }

    @Test
    void multilineSemanticRecordIsNeverSplitIntoPartialLines() {
        ResourceReceipt receipt = new ResourceReceipt(ResourcePath.parse("/result/atomic"),
                "g1", "record", 1, 2L, List.of("id", "details"), "cursor-atomic",
                "client_visible", "complete", 0L, 1L, List.of("minecraft:apple"));
        String record = "id: minecraft:apple\n  nutrition: 4\n  saturation: 2.4";
        ModelToolResultView view = new ModelToolResultView(
                "status: success\n" + record + "\nreceipt: available",
                List.of(receipt), List.of(record), 0);

        int minimum = allocator.minimumTokens(List.of(view));
        ToolGroupBudgetAllocator.Allocation omitted = allocator.allocate(
                List.of(view), minimum + record.length() - 1);
        ToolGroupBudgetAllocator.Allocation included = allocator.allocate(
                List.of(view), minimum + record.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);

        assertFalse(omitted.views().getFirst().text().contains("nutrition: 4"));
        assertTrue(included.views().getFirst().text().contains(record));
        assertEquals(List.of(record), included.views().getFirst().semanticUnits());
    }

    private static ModelToolResultView view(String id, String label) {
        ResourceReceipt receipt = new ResourceReceipt(ResourcePath.parse("/result/" + id),
                "g1", "table", 2, 100L, List.of("id", "name"), "cursor-" + id,
                "client_visible", "complete", 0L, 2L, List.of(label + "-one", label + "-two"));
        String text = "status: success\n" + label + "-one\n" + label
                + "-partial-unit-" + "x".repeat(100) + "\n" + label + "-three";
        return new ModelToolResultView(text, List.of(receipt), text.length());
    }
}
