package dev.openallay.tool.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.ContextCapability;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.projection.ResourceModelProjector;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.vfs.ResourceEntry;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceMountRegistry;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceView;
import dev.openallay.resource.vfs.ResourceViewScope;
import dev.openallay.tool.ToolRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceToolsTest {
    @Test
    void exposesProviderSafeAliasesAndPublishesBatchTruthBeforeProjection() {
        try (Fixture fixture = fixture()) {
            ToolRegistry registry = new ToolRegistry();
            registry.registerResourceTools("test:vfs", fixture.resources());
            LocalAgentToolExecutor executor = new LocalAgentToolExecutor(registry, new Gson());

            assertEquals(Set.of("resource_list", "resource_read", "resource_glob", "resource_grep", "resource_query"),
                    executor.definitions().stream().map(definition -> definition.name()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(
                    ContextCapability.REGISTRIES,
                    ContextCapability.RECIPES,
                    ContextCapability.PLAYER,
                    ContextCapability.OBSERVABLE_GAME_STATE), executor.requiredContext());

            JsonObject arguments = new JsonObject();
            JsonArray paths = new JsonArray();
            paths.add("/item/example/berry");
            paths.add("/item/example/missing");
            arguments.add("paths", paths);
            var result = executor.execute("resource_read", arguments,
                    ToolInvocationContext.developmentConsole("request-1"), new CancellationSignal()).join();

            assertEquals(1, fixture.store().records(fixture.scope()).size());
            assertEquals("success", result.normalized().get("status").getAsString());
            assertEquals(2, result.normalized().getAsJsonObject("value").getAsJsonArray("items").size());
            assertEquals("failure", result.normalized().getAsJsonObject("value")
                    .getAsJsonArray("items").get(1).getAsJsonObject().get("status").getAsString());
            JsonObject exactRead = result.normalized().getAsJsonObject("value")
                    .getAsJsonArray("items").get(0).getAsJsonObject()
                    .getAsJsonObject("value");
            assertEquals("none", exactRead.get("presentation").getAsString());
            assertTrue(exactRead.getAsJsonObject("presentationReferences").isEmpty());
            assertNotNull(result.uiReference().resultPath());
            assertTrue(result.modelView().text().contains("result: /result/"));
            assertTrue(result.modelView().text().contains("authority: deterministic_test"));
            assertFalse(result.modelView().text().contains("\"status\""));

            String firstResultPath = result.uiReference().resultPath().toString();
            JsonObject readReceipt = new JsonObject();
            JsonArray resultPaths = new JsonArray();
            resultPaths.add(firstResultPath);
            readReceipt.add("paths", resultPaths);
            var continuation = executor.execute("resource_read", readReceipt,
                    ToolInvocationContext.developmentConsole("request-2"), new CancellationSignal()).join();
            assertFalse(continuation.failure());
            assertEquals(List.of(ResourcePath.parse(firstResultPath)),
                    fixture.store().records(fixture.scope()).get(1).lineage().priorResultPaths());
        }
    }

    @Test
    void executesIndependentListGlobGrepAndQueryBatchesInSingleCalls() {
        try (Fixture fixture = fixture()) {
            ResourceListTool list = new ResourceListTool(fixture.resources());
            var listed = (dev.openallay.tool.ToolResult.Success<ResourceToolOutput>) list.invoke(
                    ToolInvocationContext.developmentConsole("list"),
                    new ResourceListTool.Input(List.of("/item", "/missing"), true));
            assertEquals(2, listed.value().items().size());

            ResourceGlobTool glob = new ResourceGlobTool(fixture.resources());
            var globbed = (dev.openallay.tool.ToolResult.Success<ResourceToolOutput>) glob.invoke(
                    ToolInvocationContext.developmentConsole("glob"),
                    new ResourceGlobTool.Input(List.of(
                            new ResourceGlobTool.Pattern("/item/**", ResourceKind.RECORD),
                            new ResourceGlobTool.Pattern("/item/example/*", null)), null));
            assertEquals(2, globbed.value().items().size());

            ResourceGrepTool grep = new ResourceGrepTool(fixture.resources());
            var searched = (dev.openallay.tool.ToolResult.Success<ResourceToolOutput>) grep.invoke(
                    ToolInvocationContext.developmentConsole("grep"),
                    new ResourceGrepTool.Input(List.of(new ResourceGrepTool.Search(
                            List.of("/item"), "berry", dev.openallay.resource.vfs.ResourceSearchIndex.Mode.TOKEN,
                            List.of("/name"))), null));
            assertEquals(1, searched.value().items().size());

            ResourceQueryTool query = new ResourceQueryTool(fixture.resources());
            var queried = (dev.openallay.tool.ToolResult.Success<ResourceToolOutput>) query.invoke(
                    ToolInvocationContext.developmentConsole("query"),
                    new ResourceQueryTool.Input(List.of(new ResourceQueryTool.Plan(
                            List.of("/item"), List.of(
                                    new ResourceQueryTool.Stage("sort", null, "/nutrition", null, null,
                                            null, dev.openallay.resource.query.ResourceQueryStage.Direction.DESC,
                                            null, null, null, null, null),
                                    new ResourceQueryTool.Stage("take", null, null, null, null,
                                            null, null, null, null, 1, null, null)))), null));
            assertEquals(1, queried.value().items().size());
            assertEquals(4, fixture.store().records(fixture.scope()).size());
        }
    }

    @Test
    void semanticCursorContinuesThePublishedResultAcrossToolCallsInTheSameRequest() {
        JsonObject initial = initialGlobArguments();
        int oneRecordBudget;
        try (Fixture probe = fixture()) {
            ToolRegistry registry = new ToolRegistry();
            registry.registerResourceTools("test:vfs", probe.resources());
            LocalAgentToolExecutor executor = new LocalAgentToolExecutor(registry, new Gson());
            executor.execute("resource_glob", initial,
                    ToolInvocationContext.developmentConsole("cursor-budget-probe"),
                    new CancellationSignal()).join();
            oneRecordBudget = smallestBudgetFor(
                    probe.store().records(probe.scope()).getFirst(), 1);
        }

        try (Fixture fixture = fixture(oneRecordBudget)) {
            ToolRegistry registry = new ToolRegistry();
            registry.registerResourceTools("test:vfs", fixture.resources());
            LocalAgentToolExecutor executor = new LocalAgentToolExecutor(registry, new Gson());

            var first = executor.execute("resource_glob", initial,
                    ToolInvocationContext.developmentConsole("cursor-1"), new CancellationSignal()).join();
            String cursor = first.modelView().receipts().getFirst().nextCursor();
            assertNotNull(cursor, first.modelView().text());
            assertEquals(1, first.modelView().receipts().getFirst().returned());
            assertEquals(2, first.modelView().receipts().getFirst().total());
            assertTrue(first.modelView().text().getBytes(StandardCharsets.UTF_8).length <= oneRecordBudget);

            JsonObject continuation = new JsonObject();
            continuation.addProperty("cursor", cursor);
            var second = executor.execute("resource_read", continuation,
                    ToolInvocationContext.developmentConsole("cursor-2"), new CancellationSignal()).join();

            assertFalse(second.failure());
            assertEquals(1, second.modelView().receipts().getFirst().returned());
            assertEquals(2, second.modelView().receipts().getFirst().total());
            assertEquals(1L, second.modelView().receipts().getFirst().fromInclusive());
            assertEquals(2L, second.modelView().receipts().getFirst().toExclusive());
            assertTrue(second.modelView().receipts().getFirst().nextCursor() == null);
            assertTrue(second.modelView().receipts().getFirst().recordIdentities().getFirst()
                    .contains("/item/example/*"));
            assertEquals(first.uiReference().resultPath(),
                    fixture.store().records(fixture.scope()).get(1).lineage().priorResultPaths().getFirst());
        }
    }

    private static JsonObject initialGlobArguments() {
        return JsonParser.parseString("""
                {"patterns":[
                  {"pattern":"/item/**"},
                  {"pattern":"/item/example/*"}
                ]}
                """).getAsJsonObject();
    }

    private static int smallestBudgetFor(
            dev.openallay.resource.result.ResourceResultRecord record,
            int returnedRecords) {
        ResourceModelProjector projector = new ResourceModelProjector();
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
        assertEquals(returnedRecords, projector.plan(record, 0, 0, low, null).returned());
        return low;
    }

    private static Fixture fixture() {
        return fixture(64_000);
    }

    private static Fixture fixture(int projectionTokenBudget) {
        UUID actor = UUID.randomUUID();
        ResourceResultStore.Scope resultScope = new ResourceResultStore.Scope(actor, "main", 1);
        ResourceResultStore store = new ResourceResultStore();
        store.openScope(resultScope);
        ResourceMountRegistry mounts = new ResourceMountRegistry();
        mounts.register(itemMount());
        mounts.publish("item");
        ResourceView view = mounts.openView(new ResourceViewScope(
                actor, "main", "request", 1, "CLIENT", Set.of("read"), NOW, () -> false));
        dev.openallay.resource.cursor.ResourceCursorStore cursors =
                new dev.openallay.resource.cursor.ResourceCursorStore();
        RequestResourceContext resources = (invocation, cancellation) ->
                new RequestResourceContext.Session(
                        view, store, resultScope, evidence(), cursors, projectionTokenBudget);
        return new Fixture(view, store, resultScope, resources, cursors);
    }

    private static ResourceMount itemMount() {
        return new ResourceMount() {
            @Override public ResourcePath root() { return ResourcePath.of("item"); }
            @Override public ResourceSnapshot snapshot() {
                ResourcePath berry = ResourcePath.parse("/item/example/berry");
                ResourcePath stew = ResourcePath.parse("/item/example/stew");
                TreeMap<ResourcePath, ResourceNode> nodes = new TreeMap<>();
                nodes.put(ResourcePath.of("item"), new ResourceNode(
                        ResourcePath.of("item"), ResourceKind.DIRECTORY, new ResourceValue.DirectoryValue(2),
                        List.of(new ResourceEntry(berry, ResourceKind.RECORD, "berry"),
                                new ResourceEntry(stew, ResourceKind.RECORD, "stew")),
                        List.of(), evidence(), ResourcePresentation.none()));
                nodes.put(berry, record(berry, "Sweet Berry", 5));
                nodes.put(stew, record(stew, "Mushroom Stew", 6));
                return new ResourceSnapshot(ResourcePath.of("item"), "items-1", NOW, nodes);
            }
        };
    }

    private static ResourceNode record(ResourcePath path, String name, int nutrition) {
        return new ResourceNode(path, ResourceKind.RECORD,
                new ResourceValue.RecordValue(Map.of(
                        "name", new ResourceValue.Scalar(name),
                        "nutrition", ResourceValue.Scalar.number(nutrition))),
                List.of(), List.of(), evidence(), ResourcePresentation.none());
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(
                DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE, NOW,
                "openallay:resource_tool_test", "openallay:resource_tool_test", "26.2", "test", Map.of());
    }

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    private record Fixture(
            ResourceView view,
            ResourceResultStore store,
            ResourceResultStore.Scope scope,
            RequestResourceContext resources,
            dev.openallay.resource.cursor.ResourceCursorStore cursors) implements AutoCloseable {
        @Override public void close() {
            view.close();
            cursors.close();
            store.close();
        }
    }
}
