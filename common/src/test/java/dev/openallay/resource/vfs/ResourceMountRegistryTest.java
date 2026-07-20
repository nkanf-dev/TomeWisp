package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceMountRegistryTest {
    @Test
    void viewRetainsCapturedGenerationAcrossReload() {
        MutableMount mount = new MutableMount("item", "g1", "Apple");
        ResourceMountRegistry registry = new ResourceMountRegistry();
        registry.register(mount);
        registry.publish("item");
        ResourceView view = registry.openView(scope());

        mount.generation = "g2";
        mount.label = "New Apple";
        registry.publish("item");

        assertEquals("Apple", scalar(view.require(ResourcePath.parse("/item/minecraft/apple"))));
        try (ResourceView current = registry.openView(scope())) {
            assertEquals("New Apple", scalar(current.require(ResourcePath.parse("/item/minecraft/apple"))));
        }
        view.close();
        assertThrows(ResourceOperationException.class,
                () -> view.require(ResourcePath.parse("/item/minecraft/apple")));
    }

    @Test
    void rejectsDuplicateMountAndInvalidPublicationWithoutReplacingCurrent() {
        MutableMount mount = new MutableMount("item", "g1", "Apple");
        ResourceMountRegistry registry = new ResourceMountRegistry();
        registry.register(mount);
        assertThrows(IllegalArgumentException.class, () -> registry.register(mount));
        registry.publish("item");
        mount.invalid = true;
        assertThrows(IllegalArgumentException.class, () -> registry.publish("item"));
        try (ResourceView view = registry.openView(scope())) {
            assertEquals("g1", view.generationIds().get("item"));
        }
    }

    private static String scalar(ResourceNode node) {
        return (String) ((ResourceValue.Scalar) node.truth()).value();
    }

    static ResourceViewScope scope() {
        return new ResourceViewScope(UUID.randomUUID(), "main", "request", 1,
                "CLIENT_LOCAL", Set.of("item"), Instant.EPOCH, () -> false);
    }

    static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.EPOCH, "openallay:test", "openallay:test", "26.2", "test", Map.of());
    }

    private static final class MutableMount implements ResourceMount {
        private final ResourcePath root;
        private String generation;
        private String label;
        private boolean invalid;

        private MutableMount(String mount, String generation, String label) {
            this.root = ResourcePath.of(mount);
            this.generation = generation;
            this.label = label;
        }

        @Override
        public ResourcePath root() {
            return root;
        }

        @Override
        public ResourceSnapshot snapshot() {
            NavigableMap<ResourcePath, ResourceNode> nodes = new TreeMap<>();
            ResourcePath child = ResourcePath.parse("/item/minecraft/apple");
            nodes.put(root, new ResourceNode(root, ResourceKind.DIRECTORY,
                    new ResourceValue.DirectoryValue(1),
                    List.of(new ResourceEntry(child, ResourceKind.SCALAR, label)), List.of(), evidence(),
                    ResourcePresentation.none()));
            if (!invalid) {
                nodes.put(child, new ResourceNode(child, ResourceKind.SCALAR,
                        new ResourceValue.Scalar(label), List.of(), List.of(), evidence(),
                        ResourcePresentation.none()));
            }
            return new ResourceSnapshot(root, generation, Instant.EPOCH, nodes);
        }
    }
}
