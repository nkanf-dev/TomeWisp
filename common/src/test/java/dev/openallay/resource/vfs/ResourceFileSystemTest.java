package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ResourceFileSystemTest {
    @Test
    void batchesExactReadsAndKeepsFailuresLocal() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ResourceView view = view(cancelled)) {
            ResourceFileSystem fileSystem = new ResourceFileSystem();
            List<ResourceFileSystem.OperationResult<ResourceFileSystem.ReadResult>> results = fileSystem.read(view, List.of(
                    new ResourceReadRequest(ResourcePath.parse("/item/example/berry"), List.of("/nutrition")),
                    ResourceReadRequest.full(ResourcePath.parse("/missing/example/nope")),
                    ResourceReadRequest.full(ResourcePath.parse("/result/r1")),
                    new ResourceReadRequest(ResourcePath.parse("/item/example/berry"), List.of("/unknown"))));

            assertEquals(4, results.size());
            assertTrue(results.get(0).succeeded());
            ResourceValue.RecordValue selected = (ResourceValue.RecordValue) results.get(0).value().value();
            assertEquals(ResourceValue.Scalar.number(5), selected.fields().get("/nutrition"));
            assertEquals("resource_not_found", results.get(1).failure().code());
            assertTrue(results.get(2).succeeded());
            assertEquals("field_unavailable", results.get(3).failure().code());
            assertEquals("/unknown", results.get(3).failure().field());
        }
    }

    @Test
    void listsAndGlobsWithStableOrderingAcrossOrdinaryRawAndResultMounts() {
        try (ResourceView view = view(new AtomicBoolean())) {
            ResourceFileSystem fileSystem = new ResourceFileSystem();
            ResourceDirectoryPage page = fileSystem.list(view, List.of(ResourcePath.of("item"))).getFirst().value();
            assertEquals(List.of("/item/example/berry", "/item/example/stew"),
                    page.entries().stream().map(entry -> entry.path().toString()).toList());

            List<ResourcePath> allItems = fileSystem.glob(view, List.of(
                    ResourceGlobPattern.compile("/item/**"))).getFirst().value();
            assertEquals(List.of("/item", "/item/example/berry", "/item/example/stew"),
                    allItems.stream().map(ResourcePath::toString).toList());

            assertEquals(List.of("/mod/example/raw/data/example/foods.json"), fileSystem.glob(view, List.of(
                    ResourceGlobPattern.compile("/mod/*/raw/**/foods.?son"))).getFirst().value().stream()
                    .map(ResourcePath::toString).toList());
            assertEquals(List.of("/result/r1"), fileSystem.glob(view, List.of(
                    ResourceGlobPattern.compile("/result/r?"))).getFirst().value().stream()
                    .map(ResourcePath::toString).toList());
        }
    }

    @Test
    void grepSupportsLiteralAndTokenModesAndRequestedFields() {
        try (ResourceView view = view(new AtomicBoolean())) {
            ResourceFileSystem fileSystem = new ResourceFileSystem();
            ResourceSearchIndex.Request literal = new ResourceSearchIndex.Request(
                    List.of(ResourcePath.of("item")), "sweet berry", ResourceSearchIndex.Mode.LITERAL,
                    List.of("/displayName"));
            ResourceSearchIndex.Request token = new ResourceSearchIndex.Request(
                    List.of(ResourcePath.of("result")), "farmer delight", ResourceSearchIndex.Mode.TOKEN, List.of());

            assertEquals("/item/example/berry", fileSystem.grep(view, List.of(literal)).getFirst()
                    .value().getFirst().path().toString());
            assertEquals("/result/r1", fileSystem.grep(view, List.of(token)).getFirst()
                    .value().getFirst().path().toString());
        }
    }

    @Test
    void cancellationProducesStructuredFailureWithoutPoisoningOtherViews() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ResourceView view = view(cancelled)) {
            cancelled.set(true);
            ResourceFileSystem.OperationResult<ResourceFileSystem.ReadResult> result = new ResourceFileSystem()
                    .read(view, List.of(ResourceReadRequest.full(ResourcePath.parse("/item/example/berry"))))
                    .getFirst();
            assertFalse(result.succeeded());
            assertEquals("agent_cancelled", result.failure().code());
        }
        try (ResourceView healthy = view(new AtomicBoolean())) {
            assertTrue(new ResourceFileSystem().read(healthy,
                    List.of(ResourceReadRequest.full(ResourcePath.parse("/item/example/berry"))))
                    .getFirst().succeeded());
        }
    }

    private static ResourceView view(AtomicBoolean cancelled) {
        ResourceMountRegistry registry = new ResourceMountRegistry();
        registry.register(mount("item", Map.of(
                ResourcePath.of("item"), directory("item", List.of("berry", "stew")),
                ResourcePath.parse("/item/example/berry"), record("/item/example/berry", Map.of(
                        "displayName", new ResourceValue.Scalar("Sweet Berry"),
                        "nutrition", ResourceValue.Scalar.number(5))),
                ResourcePath.parse("/item/example/stew"), record("/item/example/stew", Map.of(
                        "displayName", new ResourceValue.Scalar("Mushroom Stew"),
                        "nutrition", ResourceValue.Scalar.number(6))))));
        registry.register(mount("mod", Map.of(
                ResourcePath.of("mod"), directoryAt(ResourcePath.of("mod"), List.of(
                        new ResourceEntry(ResourcePath.parse("/mod/example/raw/data/example/foods.json"),
                                ResourceKind.DOCUMENT, "foods.json"))),
                ResourcePath.parse("/mod/example/raw/data/example/foods.json"), document(
                        "/mod/example/raw/data/example/foods.json", "raw", "Farmer Delight foods"))));
        registry.register(mount("result", Map.of(
                ResourcePath.of("result"), directoryAt(ResourcePath.of("result"), List.of(
                        new ResourceEntry(ResourcePath.parse("/result/r1"), ResourceKind.DOCUMENT, "r1"))),
                ResourcePath.parse("/result/r1"), document("/result/r1", "query", "Farmer's Delight result"))));
        registry.publish("item");
        registry.publish("mod");
        registry.publish("result");
        return registry.openView(new ResourceViewScope(UUID.randomUUID(), "session", "request", 1,
                "CLIENT", java.util.Set.of("read"), Instant.parse("2026-07-20T00:00:00Z"), cancelled::get));
    }

    private static ResourceMount mount(String root, Map<ResourcePath, ResourceNode> nodes) {
        return new ResourceMount() {
            @Override public ResourcePath root() { return ResourcePath.of(root); }
            @Override public ResourceSnapshot snapshot() {
                return new ResourceSnapshot(root(), "g-" + root, Instant.parse("2026-07-20T00:00:00Z"),
                        new TreeMap<>(nodes));
            }
        };
    }

    private static ResourceNode directory(String root, List<String> itemNames) {
        return directoryAt(ResourcePath.of(root), itemNames.stream().map(name -> new ResourceEntry(
                ResourcePath.parse("/" + root + "/example/" + name), ResourceKind.RECORD, name)).toList());
    }

    private static ResourceNode directoryAt(ResourcePath path, List<ResourceEntry> entries) {
        return new ResourceNode(path, ResourceKind.DIRECTORY, new ResourceValue.DirectoryValue(entries.size()),
                entries, List.of(), evidence(), ResourcePresentation.none());
    }

    private static ResourceNode record(String path, Map<String, ResourceValue> values) {
        return new ResourceNode(ResourcePath.parse(path), ResourceKind.RECORD, new ResourceValue.RecordValue(values),
                List.of(), List.of(), evidence(), ResourcePresentation.none());
    }

    private static ResourceNode document(String path, String id, String text) {
        return new ResourceNode(ResourcePath.parse(path), ResourceKind.DOCUMENT,
                new ResourceValue.DocumentValue(id, List.of(new ResourceValue.DocumentSection(id, id, text))),
                List.of(), List.of(), evidence(), ResourcePresentation.none());
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.parse("2026-07-20T00:00:00Z"), "openallay:resource_vfs_test", "openallay:resource_vfs_test",
                "26.2", "test", Map.of());
    }
}
