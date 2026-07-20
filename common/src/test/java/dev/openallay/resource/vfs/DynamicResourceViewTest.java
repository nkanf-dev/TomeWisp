package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DynamicResourceViewTest {
    @Test
    void keepsBaseGenerationFixedWhileResultGenerationAdvances() {
        ResourceMountRegistry registry = new ResourceMountRegistry();
        MutableMount item = new MutableMount("item");
        MutableMount result = new MutableMount("result");
        registry.register(item);
        registry.register(result);
        registry.publish("item");
        registry.publish("result");
        ResourceView view = registry.openView(scope(), Set.of("result"));
        String itemGeneration = view.generation("item").id();
        String resultGeneration = view.generation("result").id();

        registry.publish("item");
        registry.publish("result");

        assertEquals(itemGeneration, view.generation("item").id());
        org.junit.jupiter.api.Assertions.assertNotEquals(resultGeneration, view.generation("result").id());
        view.close();
    }

    private static ResourceViewScope scope() {
        return new ResourceViewScope(UUID.randomUUID(), "main", "request", 1,
                "client", Set.of(), Instant.EPOCH, () -> false);
    }

    private static final class MutableMount implements ResourceMount {
        private final ResourcePath root;
        private final AtomicInteger generation = new AtomicInteger();

        private MutableMount(String root) {
            this.root = ResourcePath.of(root);
        }

        @Override public ResourcePath root() { return root; }

        @Override
        public ResourceSnapshot snapshot() {
            int id = generation.incrementAndGet();
            ResourceNode node = new ResourceNode(root, ResourceKind.DIRECTORY,
                    new ResourceValue.DirectoryValue(0), List.of(), List.of(), evidence(), ResourcePresentation.none());
            return new ResourceSnapshot(root, root.mount() + '-' + id, Instant.EPOCH,
                    new TreeMap<>(Map.of(root, node)));
        }
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.EPOCH, "openallay:test", "openallay:test", "26.2", "test", Map.of());
    }
}
