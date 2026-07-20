package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ResourceViewTest {
    @Test
    void capturesScopeAndRejectsCancelledReads() {
        AtomicBoolean cancelled = new AtomicBoolean();
        UUID actor = UUID.randomUUID();
        ResourceViewScope scope = new ResourceViewScope(actor, "main", "request", 4,
                "SERVER", Set.of("game"), Instant.EPOCH, cancelled::get);
        ResourceMountRegistry registry = new ResourceMountRegistry();
        try (ResourceView view = registry.openView(scope)) {
            assertEquals(actor, view.scope().actorId());
            cancelled.set(true);
            ResourceOperationException failure = assertThrows(ResourceOperationException.class,
                    () -> view.generation("game"));
            assertEquals("agent_cancelled", failure.code());
        }
    }
}
