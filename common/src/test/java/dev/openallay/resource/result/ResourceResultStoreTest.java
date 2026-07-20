package dev.openallay.resource.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceResultStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");

    @Test
    void publishesExactRecordBeforeProjectionAndKeepsInvocationIdentityDistinct() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        ResourceResultStore.Publication first = publication("call-1", "apple");

        String projected = store.publishBeforeProject(scope, first, path -> false, record -> {
            assertSame(record, store.require(scope, record.id()));
            return record.path().toString();
        });
        ResourceResultRecord second = store.publish(scope, publication("call-2", "apple"));

        ResourceResultRecord publishedFirst = store.require(scope, ResourcePath.parse(projected));
        assertNotEquals(publishedFirst.id(), second.id());
        assertNotEquals(publishedFirst.invocationId(), second.invocationId());
        assertEquals(publishedFirst.contentDigest(), second.contentDigest());
        assertSame(publishedFirst.node().truth(), second.node().truth());
        assertEquals(1, store.contentObjectCount());
    }

    @Test
    void projectionFailureDoesNotRollBackAlreadyPublishedTruth() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);

        assertThrows(IllegalStateException.class, () -> store.publishBeforeProject(
                scope,
                publication("call-1", "apple"),
                path -> false,
                record -> {
                    assertSame(record, store.require(scope, record.id()));
                    throw new IllegalStateException("projection failed");
                }));

        assertEquals(1, store.records(scope).size());
    }

    @Test
    void acceptsOnlyPublishedAcyclicLineageInTheSameScope() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        ResourcePath source = ResourcePath.parse("/item/minecraft/apple");

        ResourceResultLineage missingSource = lineage(List.of(source), List.of(), "query", "apple");
        ResourceOperationException sourceFailure = assertThrows(ResourceOperationException.class,
                () -> store.publish(scope, publication("call-source", "apple", missingSource), path -> false));
        assertEquals("unpublished_lineage", sourceFailure.code());

        ResourceResultRecord first = store.publish(
                scope,
                publication("call-1", "apple", missingSource),
                source::equals);
        ResourceResultLineage secondLineage = lineage(
                List.of(), List.of(first.path()), "grep", first.path().toString());
        ResourceResultRecord second = store.publish(
                scope, publication("call-2", "matched apple", secondLineage));

        assertEquals(List.of(first.path()), second.lineage().priorResultPaths());
        assertTrue(second.node().links().stream().anyMatch(link ->
                link.relation().equals("derived_from") && link.target().equals(first.path())));

        ResourcePath unpublished = ResourcePath.parse("/result/r_00000000000000000000000000000000");
        ResourceOperationException priorFailure = assertThrows(ResourceOperationException.class,
                () -> store.publish(scope, publication("call-3", "bad",
                        lineage(List.of(), List.of(unpublished), "read", unpublished.toString()))));
        assertEquals("unpublished_lineage", priorFailure.code());
    }

    @Test
    void snapshotsResultRecordsAsOrdinaryVfsNodes() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        ResourceResultRecord record = store.publish(scope, publication("call-1", "apple"));
        ResourceResultMount mount = new ResourceResultMount(
                store, scope, evidence(), Clock.fixed(NOW, ZoneOffset.UTC));

        ResourceSnapshot snapshot = mount.snapshot();

        assertEquals(ResourcePath.of("result"), snapshot.root());
        assertEquals("result-1", snapshot.generationId());
        assertEquals(2, snapshot.nodes().size());
        assertSame(record.node(), snapshot.nodes().get(record.path()));
        assertEquals(record.path(), snapshot.nodes().get(ResourcePath.of("result")).children().getFirst().path());
    }

    @Test
    void laterViewsHideStaleSourceLinksWithoutChangingExactLineage() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        ResourcePath source = ResourcePath.parse("/item/example/removed");
        ResourceResultLineage lineage = lineage(List.of(source), List.of(), "read", source.toString());
        ResourceResultRecord record = store.publish(
                scope, publication("call-1", "old value", lineage), source::equals);

        ResourceResultMount laterView = new ResourceResultMount(
                store, scope, evidence(), ignored -> false);
        ResourceSnapshot snapshot = laterView.snapshot();

        assertEquals(List.of(source), record.lineage().sourcePaths());
        assertEquals(List.of(), snapshot.nodes().get(record.path()).links());
        assertEquals(record.node().truth(), snapshot.nodes().get(record.path()).truth());
    }

    @Test
    void canonicalContentDigestIgnoresRecordFieldInsertionOrder() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        java.util.LinkedHashMap<String, ResourceValue> ordered = new java.util.LinkedHashMap<>();
        ordered.put("name", new ResourceValue.Scalar("apple"));
        ordered.put("count", ResourceValue.Scalar.number(2));
        ResourceValue.RecordValue firstValue = new ResourceValue.RecordValue(ordered);
        java.util.LinkedHashMap<String, ResourceValue> reversed = new java.util.LinkedHashMap<>();
        reversed.put("count", ResourceValue.Scalar.number(2));
        reversed.put("name", new ResourceValue.Scalar("apple"));
        ResourceValue.RecordValue secondValue = new ResourceValue.RecordValue(reversed);

        ResourceResultRecord first = store.publish(scope,
                publication("call-1", ResourceKind.RECORD, firstValue, emptyLineage("one")));
        ResourceResultRecord second = store.publish(scope,
                publication("call-2", ResourceKind.RECORD, secondValue, emptyLineage("two")));

        assertEquals(first.contentDigest(), second.contentDigest());
        assertEquals(1, store.contentObjectCount());
    }

    @Test
    void oneInvocationCannotPublishTwoPublicResults() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = scope("main", 1);
        store.openScope(scope);
        store.publish(scope, publication("call-1", "apple"));

        ResourceOperationException failure = assertThrows(ResourceOperationException.class,
                () -> store.publish(scope, publication("call-1", "different")));

        assertEquals("duplicate_result_invocation", failure.code());
        assertEquals(1, store.records(scope).size());
    }

    @Test
    void malformedIdsAndLineageAreRejectedAtTheBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceResultId("apple"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceResultId.fromPath(ResourcePath.parse("/item/minecraft/apple")));
        assertThrows(IllegalArgumentException.class, () -> new ResourceResultLineage(
                List.of(ResourcePath.parse("/result/r_00000000000000000000000000000000")),
                List.of(), ResourceResultLineage.digestOperation("read", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new ResourceResultLineage(
                List.of(), List.of(ResourcePath.parse("/item/minecraft/apple")),
                ResourceResultLineage.digestOperation("read", "{}")));
        assertThrows(IllegalArgumentException.class, () -> new ResourceResultStore.Publication(
                "call-invalid",
                emptyLineage("invalid"),
                ResourceKind.TABLE,
                new ResourceValue.Scalar("not a table"),
                evidence(),
                ResourcePresentation.none()));
    }

    private static ResourceResultStore store() {
        return new ResourceResultStore(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResourceResultStore.Scope scope(String session, long connection) {
        return new ResourceResultStore.Scope(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), session, connection);
    }

    private static ResourceResultStore.Publication publication(String invocationId, String value) {
        return publication(invocationId, value, emptyLineage(invocationId));
    }

    private static ResourceResultStore.Publication publication(
            String invocationId, String value, ResourceResultLineage lineage) {
        return publication(invocationId, ResourceKind.SCALAR, new ResourceValue.Scalar(value), lineage);
    }

    private static ResourceResultStore.Publication publication(
            String invocationId, ResourceKind kind, ResourceValue value, ResourceResultLineage lineage) {
        return new ResourceResultStore.Publication(
                invocationId, lineage, kind, value, evidence(), ResourcePresentation.none());
    }

    private static ResourceResultLineage emptyLineage(String arguments) {
        return lineage(List.of(), List.of(), "test", arguments);
    }

    private static ResourceResultLineage lineage(
            List<ResourcePath> sourcePaths,
            List<ResourcePath> priorPaths,
            String operation,
            String arguments) {
        return new ResourceResultLineage(
                sourcePaths, priorPaths, ResourceResultLineage.digestOperation(operation, arguments));
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(
                DataAuthority.DETERMINISTIC_TEST,
                DataCompleteness.COMPLETE,
                NOW,
                "openallay:result_test",
                "openallay:result_test",
                "26.2",
                "test",
                Map.of());
    }
}
