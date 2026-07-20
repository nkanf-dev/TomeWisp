package dev.openallay.resource.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceEntry;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceMountRegistry;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceView;
import dev.openallay.resource.vfs.ResourceViewScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ResourceQueryEngineTest {
    @Test
    void filtersSortsSelectsAndTakesRuntimeDiscoveredUnknownFields() {
        try (ResourceView view = view()) {
            ResourceQueryPlan plan = new ResourceQueryPlan(List.of(ResourcePath.of("item")), List.of(
                    new ResourceQueryStage.Filter("/modded/satiety", ResourceQueryStage.Operator.GT,
                            ResourceValue.Scalar.number(4)),
                    new ResourceQueryStage.Sort("/modded/satiety", ResourceQueryStage.Direction.DESC),
                    new ResourceQueryStage.Select(List.of("/@path", "/modded/satiety")),
                    new ResourceQueryStage.Take(1)));

            ResourceQueryEngine.Result result = new ResourceQueryEngine().execute(view, plan);
            assertEquals(3, result.sourceRows());
            assertEquals(List.of(2, 2, 2, 1), result.stages().stream()
                    .map(ResourceQueryEngine.StageCardinality::outputRows).toList());
            assertEquals("/item/example/stew", scalar(result.rows().getFirst().fields().get("/@path")));
            assertTrue(result.sourceSchema().availablePaths().contains("/modded/satiety"));
        }
    }

    @Test
    void expandsGroupsAndAggregatesWithoutDomainFieldWhitelists() {
        try (ResourceView view = view()) {
            ResourceQueryEngine engine = new ResourceQueryEngine();
            ResourceQueryEngine.Result expanded = engine.execute(view, new ResourceQueryPlan(
                    List.of(ResourcePath.of("result")), List.of(
                            new ResourceQueryStage.Expand("/effects"),
                            new ResourceQueryStage.Group("/effects"))));
            assertEquals(List.of("poison", "regeneration"), expanded.rows().stream()
                    .map(row -> scalar(row.fields().get("/effects"))).toList());

            ResourceQueryEngine.Result aggregate = engine.execute(view, new ResourceQueryPlan(
                    List.of(ResourcePath.of("item")), List.of(
                            new ResourceQueryStage.Aggregate("/modded/satiety",
                                    ResourceQueryStage.AggregateFunction.MAX, null))));
            assertEquals(new BigDecimal("8"), scalarValue(aggregate.rows().getFirst().fields().get("/value")));
        }
    }

    @Test
    void followsLinksAcrossMountsAndStopsCyclesByPathRelationAndGeneration() {
        try (ResourceView view = view()) {
            ResourceQueryEngine engine = new ResourceQueryEngine();
            ResourceQueryEngine.Result recipe = engine.execute(view, new ResourceQueryPlan(
                    List.of(ResourcePath.parse("/item/example/berry")),
                    List.of(new ResourceQueryStage.Follow("recipe", 1))));
            assertEquals("/recipe/example/berry_pie", recipe.rows().getFirst().source().toString());

            ResourceQueryEngine.Result cycle = engine.execute(view, new ResourceQueryPlan(
                    List.of(ResourcePath.parse("/graph/a")),
                    List.of(new ResourceQueryStage.Follow("next", 10))));
            assertEquals(List.of("/graph/b", "/graph/a"), cycle.rows().stream()
                    .map(row -> row.source().toString()).toList());
        }
    }

    @Test
    void batchesMultipleRootsAndReportsMixedTypesWithoutLosingSiblingResult() {
        try (ResourceView view = view()) {
            ResourceQueryPlan valid = new ResourceQueryPlan(
                    List.of(ResourcePath.of("item"), ResourcePath.of("result")),
                    List.of(new ResourceQueryStage.Search("poison", null)));
            ResourceQueryPlan invalid = new ResourceQueryPlan(List.of(ResourcePath.of("mixed")),
                    List.of(new ResourceQueryStage.Sort("/score", ResourceQueryStage.Direction.ASC)));

            List<ResourceQueryEngine.BatchResult> results = new ResourceQueryEngine().execute(view, List.of(valid, invalid));
            assertTrue(results.get(0).failure() == null);
            assertFalse(results.get(0).value().rows().isEmpty());
            assertEquals("mixed_field_types", results.get(1).failure().code());
            assertEquals("/score", results.get(1).failure().field());
            assertTrue(results.get(1).failure().message().contains("mixed"));
        }
    }

    private static ResourceView view() {
        ResourceMountRegistry registry = new ResourceMountRegistry();
        registry.register(mount("recipe", Map.of(
                ResourcePath.of("recipe"), directory("recipe", List.of(
                        new ResourceEntry(ResourcePath.parse("/recipe/example/berry_pie"), ResourceKind.RECORD, "pie"))),
                ResourcePath.parse("/recipe/example/berry_pie"), record("/recipe/example/berry_pie",
                        Map.of("name", new ResourceValue.Scalar("Berry Pie")), List.of()))));
        registry.publish("recipe");
        registry.register(mount("item", Map.of(
                ResourcePath.of("item"), directory("item", List.of(
                        entry("/item/example/berry"), entry("/item/example/stew"), entry("/item/example/tea"))),
                ResourcePath.parse("/item/example/berry"), record("/item/example/berry", Map.of(
                        "name", new ResourceValue.Scalar("Poison Berry"),
                        "modded", new ResourceValue.RecordValue(Map.of("satiety", ResourceValue.Scalar.number(5)))),
                        List.of(new ResourceLink("recipe", ResourcePath.parse("/recipe/example/berry_pie"), "pie"))),
                ResourcePath.parse("/item/example/stew"), record("/item/example/stew", Map.of(
                        "name", new ResourceValue.Scalar("Stew"),
                        "modded", new ResourceValue.RecordValue(Map.of("satiety", ResourceValue.Scalar.number(8)))), List.of()),
                ResourcePath.parse("/item/example/tea"), record("/item/example/tea", Map.of(
                        "name", new ResourceValue.Scalar("Tea"),
                        "modded", new ResourceValue.RecordValue(Map.of("satiety", ResourceValue.Scalar.number(2)))), List.of()))));
        registry.publish("item");
        registry.register(mount("result", Map.of(
                ResourcePath.of("result"), directory("result", List.of(entry("/result/r-effects"))),
                ResourcePath.parse("/result/r-effects"), record("/result/r-effects", Map.of(
                        "name", new ResourceValue.Scalar("Poison support"),
                        "effects", new ResourceValue.ListValue(List.of(
                                new ResourceValue.Scalar("poison"), new ResourceValue.Scalar("regeneration"),
                                new ResourceValue.Scalar("poison")))), List.of()))));
        registry.publish("result");
        registry.register(mount("mixed", Map.of(
                ResourcePath.of("mixed"), directory("mixed", List.of(entry("/mixed/a"), entry("/mixed/b"))),
                ResourcePath.parse("/mixed/a"), record("/mixed/a", Map.of("score", ResourceValue.Scalar.number(1)), List.of()),
                ResourcePath.parse("/mixed/b"), record("/mixed/b", Map.of("score", new ResourceValue.Scalar("high")), List.of()))));
        registry.publish("mixed");
        registry.register(mount("graph", Map.of(
                ResourcePath.of("graph"), directory("graph", List.of(entry("/graph/a"), entry("/graph/b"))),
                ResourcePath.parse("/graph/a"), record("/graph/a", Map.of("name", new ResourceValue.Scalar("a")),
                        List.of(new ResourceLink("next", ResourcePath.parse("/graph/b"), "b"))),
                ResourcePath.parse("/graph/b"), record("/graph/b", Map.of("name", new ResourceValue.Scalar("b")),
                        List.of(new ResourceLink("next", ResourcePath.parse("/graph/a"), "a"))))));
        registry.publish("graph");
        AtomicBoolean cancelled = new AtomicBoolean();
        return registry.openView(new ResourceViewScope(UUID.randomUUID(), "session", "request", 1,
                "CLIENT", Set.of("read"), Instant.parse("2026-07-20T00:00:00Z"), cancelled::get));
    }

    private static ResourceEntry entry(String path) {
        return new ResourceEntry(ResourcePath.parse(path), ResourceKind.RECORD, path.substring(path.lastIndexOf('/') + 1));
    }

    private static ResourceNode directory(String root, List<ResourceEntry> entries) {
        return new ResourceNode(ResourcePath.of(root), ResourceKind.DIRECTORY,
                new ResourceValue.DirectoryValue(entries.size()), entries, List.of(), evidence(), ResourcePresentation.none());
    }

    private static ResourceNode record(String path, Map<String, ResourceValue> fields, List<ResourceLink> links) {
        return new ResourceNode(ResourcePath.parse(path), ResourceKind.RECORD, new ResourceValue.RecordValue(fields),
                List.of(), links, evidence(), ResourcePresentation.none());
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

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.parse("2026-07-20T00:00:00Z"), "openallay:resource_query_test", "openallay:resource_query_test",
                "26.2", "test", Map.of());
    }

    private static String scalar(ResourceValue value) {
        return scalarValue(value).toString();
    }

    private static Object scalarValue(ResourceValue value) {
        return ((ResourceValue.Scalar) value).value();
    }
}
