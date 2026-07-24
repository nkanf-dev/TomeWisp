package dev.openallay.script.extension;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import java.util.List;

/**
 * Trusted Java-side projector for optional extension data already detached into a request context.
 *
 * <p>This method runs on the Agent worker and therefore must only inspect immutable values from
 * {@link ToolInvocationContext}. It must not call Minecraft or mod APIs, use reflection to reach
 * live objects, or retain thread-owned state. Loader integrations that need live state capture and
 * detach it on the owning Minecraft thread before constructing the request context. Rhino receives
 * the returned detached record/collection graph through OpenAllay's component-only host adapter;
 * generic methods, classes, and reflection authority are never exposed.
 */
public interface JavascriptDataModule {
    String id();

    Snapshot capture(ToolInvocationContext context);

    record Snapshot(Object value, List<EvidenceMetadata> evidence) {
        public Snapshot {
            value = java.util.Objects.requireNonNull(value, "value");
            evidence = List.copyOf(evidence);
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException("JavaScript module snapshot requires evidence");
            }
        }

    }
}
