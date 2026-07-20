package dev.openallay.resource.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceResultLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void lookupIsBoundToActorSessionAndConnection() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope owner = new ResourceResultStore.Scope(ACTOR, "main", 1);
        ResourceResultStore.Scope otherActor = new ResourceResultStore.Scope(OTHER_ACTOR, "main", 1);
        ResourceResultStore.Scope otherSession = new ResourceResultStore.Scope(ACTOR, "other", 1);
        ResourceResultStore.Scope otherConnection = new ResourceResultStore.Scope(ACTOR, "main", 2);
        store.openScope(owner);
        store.openScope(otherActor);
        store.openScope(otherSession);
        store.openScope(otherConnection);
        ResourceResultRecord record = store.publish(owner, publication("call-1"));

        for (ResourceResultStore.Scope unauthorized : List.of(otherActor, otherSession, otherConnection)) {
            ResourceOperationException failure = assertThrows(
                    ResourceOperationException.class, () -> store.require(unauthorized, record.id()));
            assertEquals("resource_forbidden", failure.code());
        }
        assertEquals(record, store.require(owner, record.id()));
    }

    @Test
    void sessionDeletionReleasesContentAndRejectsLateReadsAndPublishes() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = new ResourceResultStore.Scope(ACTOR, "main", 1);
        store.openScope(scope);
        ResourceResultRecord record = store.publish(scope, publication("call-1"));
        assertEquals(1, store.contentObjectCount());

        store.deleteSession(ACTOR, "main", 1);

        assertEquals(0, store.contentObjectCount());
        assertFailure("stale_resource", () -> store.require(scope, record.id()));
        assertFailure("stale_resource", () -> store.publish(scope, publication("late-call")));
        assertFailure("stale_resource", () -> store.openScope(scope));
    }

    @Test
    void disconnectExpiresAllSessionsAndPreventsGenerationReuse() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope first = new ResourceResultStore.Scope(ACTOR, "main", 4);
        ResourceResultStore.Scope second = new ResourceResultStore.Scope(ACTOR, "other", 4);
        store.openScope(first);
        store.openScope(second);
        store.publish(first, publication("call-1"));
        store.publish(second, publication("call-2"));

        store.disconnect(4);

        assertEquals(0, store.contentObjectCount());
        assertFailure("stale_resource", () -> store.openScope(first));
        assertFailure("stale_resource", () -> store.publish(second, publication("late-call")));
    }

    @Test
    void shutdownIsIdempotentAndRejectsAllLateOperations() {
        ResourceResultStore store = store();
        ResourceResultStore.Scope scope = new ResourceResultStore.Scope(ACTOR, "main", 1);
        store.openScope(scope);
        store.publish(scope, publication("call-1"));

        store.close();
        store.close();

        assertTrue(store.isShutdown());
        assertEquals(0, store.contentObjectCount());
        assertFailure("stale_resource", () -> store.openScope(scope));
    }

    @Test
    void durableDisplayReceiptCannotResurrectOrRecomputeAResult() {
        ResourceResultStore firstProcess = store();
        ResourceResultStore.Scope scope = new ResourceResultStore.Scope(ACTOR, "main", 1);
        firstProcess.openScope(scope);
        ResourceResultRecord original = firstProcess.publish(scope, publication("call-1"));
        ResourceResultRecord.DisplayReceipt receipt = original.displayReceipt();
        firstProcess.close();

        ResourceResultStore restarted = store();
        restarted.openScope(scope);
        assertEquals(original.path().toString(), receipt.expiredPath());
        assertFalse(Arrays.stream(receipt.getClass().getRecordComponents()).anyMatch(component ->
                ResourceValue.class.isAssignableFrom(component.getType())
                        || dev.openallay.resource.vfs.ResourceNode.class.isAssignableFrom(component.getType())));
        ResourceOperationException failure = assertThrows(ResourceOperationException.class,
                () -> restarted.require(scope, ResourceResultId.fromPath(
                        dev.openallay.resource.vfs.ResourcePath.parse(receipt.expiredPath()))));
        assertEquals("stale_resource", failure.code());
        assertTrue(restarted.records(scope).isEmpty());
    }

    private static void assertFailure(String code, org.junit.jupiter.api.function.Executable executable) {
        ResourceOperationException failure = assertThrows(ResourceOperationException.class, executable);
        assertEquals(code, failure.code());
    }

    private static ResourceResultStore store() {
        return new ResourceResultStore(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResourceResultStore.Publication publication(String invocationId) {
        return new ResourceResultStore.Publication(
                invocationId,
                new ResourceResultLineage(
                        List.of(), List.of(), ResourceResultLineage.digestOperation("read", invocationId)),
                ResourceKind.SCALAR,
                new ResourceValue.Scalar("apple"),
                new EvidenceMetadata(
                        DataAuthority.DETERMINISTIC_TEST,
                        DataCompleteness.COMPLETE,
                        NOW,
                        "openallay:result_test",
                        "openallay:result_test",
                        "26.2",
                        "test",
                        Map.of()),
                ResourcePresentation.none());
    }
}
