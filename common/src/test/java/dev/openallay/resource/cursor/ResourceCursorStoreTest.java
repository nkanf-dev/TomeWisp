package dev.openallay.resource.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceMountRegistry;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceView;
import dev.openallay.resource.vfs.ResourceViewScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceCursorStoreTest {
    @Test
    void bindsOpaqueCursorToOwnerRequestConnectionViewAndQuery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        ResourceCursorStore store = new ResourceCursorStore(new SecureRandom(), clock);
        UUID actor = UUID.randomUUID();
        try (ResourceView view = view(actor, "main", "request", 7, "g1")) {
            ResourceCursor cursor = cursor(actor, view, clock.instant().plusSeconds(60));
            String token = store.issue(cursor);

            assertNotEquals(cursor.toString(), token);
            assertEquals(cursor, store.resolve(token, actor, "main", "request", view, "query-1"));
            ResourceOperationException otherActor = assertThrows(ResourceOperationException.class,
                    () -> store.resolve(token, UUID.randomUUID(), "main", "request", view, "query-1"));
            assertEquals("invalid_cursor", otherActor.code());
            ResourceOperationException otherQuery = assertThrows(ResourceOperationException.class,
                    () -> store.resolve(token, actor, "main", "request", view, "query-2"));
            assertEquals("invalid_cursor", otherQuery.code());
        }
    }

    @Test
    void expiresAndReleasesAtRequestSessionConnectionAndShutdownBoundaries() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        ResourceCursorStore store = new ResourceCursorStore(new SecureRandom(), clock);
        UUID actor = UUID.randomUUID();
        try (ResourceView view = view(actor, "main", "request", 7, "g1")) {
            String expired = store.issue(cursor(actor, view, clock.instant().plusSeconds(1)));
            clock.now = clock.instant().plusSeconds(2);
            ResourceOperationException failure = assertThrows(ResourceOperationException.class,
                    () -> store.resolve(expired, actor, "main", "request", view, "query-1"));
            assertEquals("cursor_expired", failure.code());

            String request = store.issue(cursor(actor, view, null));
            store.releaseRequest(actor, "main", "request");
            assertInvalid(store, request, actor, view);

            String session = store.issue(cursor(actor, view, clock.instant().plusSeconds(60)));
            store.releaseSession(actor, "main");
            assertInvalid(store, session, actor, view);

            String connection = store.issue(cursor(actor, view, clock.instant().plusSeconds(60)));
            store.releaseConnection(7);
            assertInvalid(store, connection, actor, view);

            store.issue(cursor(actor, view, clock.instant().plusSeconds(60)));
            store.close();
            assertEquals(0, store.size());
        }
    }

    private static void assertInvalid(
            ResourceCursorStore store, String token, UUID actor, ResourceView view) {
        ResourceOperationException failure = assertThrows(ResourceOperationException.class,
                () -> store.resolve(token, actor, "main", "request", view, "query-1"));
        assertEquals("invalid_cursor", failure.code());
    }

    private static ResourceCursor cursor(UUID actor, ResourceView view, Instant expiresAt) {
        return new ResourceCursor(actor, "main", "request", 7, view.generationIds(), "query-1",
                ResourcePath.parse("/item/minecraft/apple"),
                ResourceCursor.PositionKind.RECORD, 12, expiresAt);
    }

    private static ResourceView view(
            UUID actor, String session, String request, long connection, String generation) {
        ResourceMountRegistry registry = new ResourceMountRegistry();
        registry.register(new FixedMount(generation));
        registry.publish("item");
        return registry.openView(new ResourceViewScope(actor, session, request, connection,
                "CLIENT_LOCAL", Set.of("item"), Instant.EPOCH, () -> false));
    }

    private record FixedMount(String generation) implements ResourceMount {
        @Override
        public ResourcePath root() {
            return ResourcePath.of("item");
        }

        @Override
        public ResourceSnapshot snapshot() {
            ResourcePath root = root();
            ResourcePath apple = ResourcePath.parse("/item/minecraft/apple");
            EvidenceMetadata evidence = new EvidenceMetadata(
                    DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE, Instant.EPOCH,
                    "openallay:test", "openallay:test", "26.2", "test", Map.of());
            var nodes = new TreeMap<ResourcePath, ResourceNode>();
            nodes.put(root, new ResourceNode(root, ResourceKind.DIRECTORY,
                    new ResourceValue.DirectoryValue(1),
                    List.of(new dev.openallay.resource.vfs.ResourceEntry(
                            apple, ResourceKind.SCALAR, "Apple")), List.of(), evidence,
                    ResourcePresentation.none()));
            nodes.put(apple, new ResourceNode(apple, ResourceKind.SCALAR,
                    new ResourceValue.Scalar("apple"), List.of(), List.of(), evidence,
                    ResourcePresentation.none()));
            return new ResourceSnapshot(root, generation, Instant.EPOCH, nodes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
