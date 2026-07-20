package dev.openallay.resource.projection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.result.ResourceResultLineage;
import dev.openallay.resource.result.ResourceResultRecord;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceModelProjectorTest {
    @Test
    void rendersGroupedSemanticTextWithEvidenceAndReceiptWithoutJsonFraming() {
        ResourceResultStore store = new ResourceResultStore();
        ResourceResultStore.Scope scope = new ResourceResultStore.Scope(UUID.randomUUID(), "main", 1);
        store.openScope(scope);
        ResourceResultRecord record = store.publish(scope, new ResourceResultStore.Publication(
                "call-1",
                new ResourceResultLineage(List.of(), List.of(),
                        ResourceResultLineage.digestOperation("read", "/item/example/berry")),
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(Map.of(
                        "name", new ResourceValue.Scalar("Sweet Berry"),
                        "nutrition", ResourceValue.Scalar.number(5))),
                evidence(),
                ResourcePresentation.none()));

        var projection = new ResourceModelProjector().project(record);

        assertTrue(projection.text().contains("name: Sweet Berry"));
        assertTrue(projection.text().contains("nutrition: 5"));
        assertTrue(projection.text().contains("authority: deterministic_test"));
        assertTrue(projection.text().contains("result: " + record.path()));
        assertTrue(projection.text().contains("returned: 1/1"));
        assertFalse(projection.text().contains("{\""));
        assertTrue(projection.receipts().getFirst().resultPath().equals(record.path()));
        store.close();
    }

    @Test
    void budgetsCompleteRecordsIncludingTheFinalContinuationReceipt() {
        ResourceResultStore store = new ResourceResultStore();
        ResourceResultStore.Scope scope = new ResourceResultStore.Scope(UUID.randomUUID(), "main", 1);
        store.openScope(scope);
        ResourceResultRecord record = store.publish(scope, new ResourceResultStore.Publication(
                "call-page",
                new ResourceResultLineage(List.of(), List.of(),
                        ResourceResultLineage.digestOperation("query", "/item")),
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(Map.of(
                        "operation", new ResourceValue.Scalar("resource_query"),
                        "items", new ResourceValue.ListValue(List.of(
                                item(0, "first-complete-record"),
                                item(1, "second-complete-record"))))),
                evidence(),
                ResourcePresentation.none()));
        ResourceModelProjector projector = new ResourceModelProjector();
        int budget = smallestBudgetFor(projector, record, 1);

        var plan = projector.plan(record, 0, 0, budget, null);
        var projection = projector.project(record, plan, "x".repeat(32));

        assertTrue(plan.cursorEligible());
        assertTrue(projection.text().contains("first-complete-record"));
        assertFalse(projection.text().contains("second-complete-record"));
        assertTrue(projection.text().getBytes(StandardCharsets.UTF_8).length <= budget);

        var tooSmall = projector.plan(record, 0, 0, budget - 1, null);
        assertTrue(tooSmall.returned() == 0);
        assertFalse(tooSmall.cursorEligible());
        assertTrue(projector.project(record, tooSmall, null).text().contains("with narrower fields"));
        store.close();
    }

    private static ResourceValue item(int index, String input) {
        return new ResourceValue.RecordValue(Map.of(
                "index", ResourceValue.Scalar.number(index),
                "input", new ResourceValue.Scalar(input),
                "status", new ResourceValue.Scalar("success")));
    }

    private static int smallestBudgetFor(
            ResourceModelProjector projector, ResourceResultRecord record, int returnedRecords) {
        int low = 1;
        int high = 64_000;
        while (low < high) {
            int candidate = low + (high - low) / 2;
            if (projector.plan(record, 0, 0, candidate, null).returned() >= returnedRecords) {
                high = candidate;
            } else {
                low = candidate + 1;
            }
        }
        return low;
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(
                DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.parse("2026-07-20T00:00:00Z"), "openallay:projection_test",
                "openallay:projection_test", "26.2", "test", Map.of());
    }
}
