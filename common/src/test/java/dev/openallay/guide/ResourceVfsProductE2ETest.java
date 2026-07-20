package dev.openallay.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.context.ContextAssembler;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.agent.context.ToolResultContextReducer;
import dev.openallay.agent.context.Utf8ContextTokenEstimator;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.context.ContextMetrics;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.game.ObservableGameStateSnapshot;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.projection.ToolGroupBudgetAllocator;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic product acceptance for the request-scoped Resource VFS architecture. */
final class ResourceVfsProductE2ETest {
    private static final Gson GSON = new Gson();
    private static final ContextBudget ONE_HUNDRED_K = new ContextBudget(100_000, 4_096);

    @Test
    void answersCrossDomainQuestionsThroughOneVfsAndPreservesNativeRecipeReference() {
        try (Fixture fixture = fixture(48, ONE_HUNDRED_K)) {
            AgentToolResult overview = fixture.execute("resource_read", """
                    {"paths":[
                      "/mod/farmersdelight",
                      "/game/runtime",
                      "/game/options/video/render_distance",
                      "/game/packs/resource/0",
                      "/game/diagnostics/position/coordinates",
                      "/player/profile"
                    ]}
                    """);
            assertFalse(overview.failure(), overview.modelView().text());
            assertEquals(6, overview.normalized().getAsJsonObject("value")
                    .getAsJsonArray("items").size());
            assertTrue(overview.modelView().text().contains("Farmer's Delight"));
            assertTrue(overview.modelView().text().contains("render_distance"));
            assertTrue(overview.modelView().text().contains("1 64 2"));

            AgentToolResult recipe = fixture.execute("resource_read", """
                    {"paths":["/recipe/minecraft/iron_block"]}
                    """);
            assertFalse(recipe.failure(), recipe.modelView().text());
            assertEquals(ResourcePresentation.Kind.RECIPE, recipe.uiReference().presentationKind());
            JsonObject exactRecipe = recipe.normalized().getAsJsonObject("value")
                    .getAsJsonArray("items").get(0).getAsJsonObject()
                    .getAsJsonObject("value");
            assertEquals("minecraft:iron_block", exactRecipe
                    .getAsJsonObject("presentationReferences").get("recipeId").getAsString());
            assertTrue(exactRecipe.getAsJsonArray("links").asList().stream()
                    .anyMatch(link -> link.getAsJsonObject().get("relation").getAsString()
                            .equals("ingredient")));

            AgentToolResult schema = fixture.execute("resource_read", """
                    {"paths":["/item/farmersdelight/food_0/@schema"]}
                    """);
            assertFalse(schema.failure(), schema.modelView().text());
            assertTrue(schema.modelView().text().contains("/properties/minecraft:saturation"));

            AgentToolResult query = fixture.execute("resource_query", """
                    {"plans":[{"roots":["/item/farmersdelight"],"pipeline":[
                      {"operation":"sort","field":"/properties/minecraft:saturation","direction":"DESC"},
                      {"operation":"take","count":1},
                      {"operation":"select","fields":["/@path","/name","/properties/minecraft:saturation"]}
                    ]}]}
                    """);
            assertFalse(query.failure(), query.modelView().text());
            assertTrue(query.modelView().text().contains("food_47"));
            assertTrue(query.modelView().text().contains("source_schema"));
        }
    }

    @Test
    void continuesLargeExactResultsAndRefinesPriorResultsWithGrepAndQuery() {
        ContextBudget smallProjection = new ContextBudget(8_000, 1_000);
        try (Fixture fixture = fixture(180, smallProjection)) {
            String paths = fixture.itemPaths(180);
            AgentToolResult first = fixture.execute(
                    "resource_read", "{\"paths\":" + paths + "}");
            var receipt = first.modelView().receipts().getFirst();
            assertNotNull(receipt.nextCursor(), first.modelView().text());
            assertTrue(receipt.returned() > 0 && receipt.returned() < receipt.total());
            assertTrue(first.uiReference().continuationAvailable());

            AgentToolResult next = fixture.execute(
                    "resource_read", "{\"cursor\":\"" + receipt.nextCursor() + "\"}");
            assertFalse(next.failure(), next.modelView().text());
            assertEquals(receipt.toExclusive(), next.modelView().receipts().getFirst().fromInclusive());
            assertEquals(first.uiReference().resultPath(),
                    fixture.resources.resultStore().records(fixture.resultScope()).get(1)
                            .lineage().priorResultPaths().getFirst());

            AgentToolResult grep = fixture.execute("resource_grep", """
                    {"searches":[{"roots":["%s"],"pattern":"food_179","mode":"LITERAL"}]}
                    """.formatted(first.uiReference().resultPath()));
            assertFalse(grep.failure(), grep.modelView().text());
            assertTrue(grep.modelView().text().contains("food_179"));

            AgentToolResult query = fixture.execute("resource_query", """
                    {"plans":[{"roots":["%s"],"pipeline":[
                      {"operation":"search","query":"food_179"},
                      {"operation":"take","count":1}
                    ]}]}
                    """.formatted(first.uiReference().resultPath()));
            assertFalse(query.failure(), query.modelView().text());
            assertTrue(query.normalized().toString().contains("food_179"));
            assertTrue(fixture.resources.resultStore().records(fixture.resultScope()).stream()
                    .skip(2)
                    .allMatch(record -> record.lineage().priorResultPaths()
                            .contains(first.uiReference().resultPath())));
        }
    }

    @Test
    void everyLongVfsContinuationFitsResolvedOneHundredKDispatchBudget() {
        try (Fixture fixture = fixture(420, ONE_HUNDRED_K)) {
            ContextAssembler assembler = new ContextAssembler(
                    new Utf8ContextTokenEstimator(),
                    new ToolResultContextReducer(),
                    new ToolGroupBudgetAllocator(),
                    ONE_HUNDRED_K);
            ArrayList<ModelMessage> messages = new ArrayList<>();
            messages.add(ModelMessage.userText("比较整合包中的大量食物数据，并保留可追溯证据。"));
            for (int turn = 0; turn < 10; turn++) {
                String callId = "vfs-" + turn;
                JsonObject arguments = JsonParser.parseString(
                        "{\"paths\":" + fixture.itemPaths(160) + "}").getAsJsonObject();
                AgentToolResult result = fixture.execute("resource_read", arguments);
                messages.add(new ModelMessage(ModelRole.ASSISTANT, List.of(
                        new ModelContent.ToolUse(callId, "resource_read", arguments))));
                messages.add(new ModelMessage(ModelRole.USER, List.of(new ModelContent.ToolResult(
                        callId,
                        result.modelView().text(),
                        result.uiReference().resultPath().toString(),
                        result.modelView().receipts(),
                        result.modelView().semanticUnits(),
                        result.failure()))));

                ContextAssembler.Assembly dispatch = assembler.assemble(
                        "OpenAllay deterministic VFS acceptance", messages, 0,
                        fixture.executor.definitions());
                assertTrue(dispatch.fits(), "turn " + turn + " exceeded the 100K input budget");
                assertTrue(dispatch.projection().estimatedTokens() <= ONE_HUNDRED_K.inputTokens());
                assertTrue(dispatch.projection().messages().stream()
                        .flatMap(message -> message.content().stream())
                        .filter(ModelContent.ToolResult.class::isInstance)
                        .map(ModelContent.ToolResult.class::cast)
                        .allMatch(toolResult -> !toolResult.text().contains("\"items\"")));
            }
            assertEquals(10, fixture.resources.resultStore().records(fixture.resultScope()).size());
        }
    }

    private static Fixture fixture(int itemCount, ContextBudget budget) {
        ToolInvocationContext context = context("vfs-product-e2e", itemCount);
        ResourceRequestRegistry resources = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry());
        long connection = resources.connectionGeneration(GroundedTestFixtures.PLAYER_ID);
        ResourceRequestRegistry.RequestHandle handle = resources.open(
                GroundedTestFixtures.PLAYER_ID,
                "e2e",
                UUID.nameUUIDFromBytes(context.correlationId().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                connection,
                "client",
                Set.of("resource_list", "resource_read", "resource_glob", "resource_grep", "resource_query"),
                budget,
                context);
        ToolRegistry tools = new ToolRegistry();
        tools.registerResourceTools("e2e:vfs", resources);
        return new Fixture(resources, handle, new LocalAgentToolExecutor(tools, GSON), context, connection);
    }

    private static ToolInvocationContext context(String correlation, int itemCount) {
        ArrayList<RegistryEntrySnapshot> entries = new ArrayList<>();
        entries.addAll(GroundedTestFixtures.registries().entries());
        entries.add(new RegistryEntrySnapshot(
                "minecraft:iron_ingot", "item", "Iron Ingot", "minecraft", "minecraft:registry"));
        for (int index = 0; index < itemCount; index++) {
            entries.add(new RegistryEntrySnapshot(
                    "farmersdelight:food_" + index,
                    "item",
                    "Farmer Food " + index,
                    "farmersdelight",
                    "minecraft:registry",
                    List.of("food " + index),
                    Set.of("farmersdelight:foods"),
                    Set.of("minecraft:food"),
                    Map.of(
                            "minecraft:nutrition", GSON.toJsonTree(index + 1),
                            "minecraft:saturation", GSON.toJsonTree(index + 0.5D))));
        }
        RegistrySnapshot registries = new RegistrySnapshot(
                GroundedTestFixtures.serverEvidence(), entries);
        ObservableGameStateSnapshot game = gameState();
        return new ToolInvocationContext(
                correlation,
                Instant.EPOCH,
                GroundedTestFixtures.fullContext().caller(),
                Optional.of(GroundedTestFixtures.player()),
                Optional.of(registries),
                Optional.of(GroundedTestFixtures.recipeSnapshot()),
                Optional.of(game),
                new ContextMetrics(entries.size(), 1, 2, 0, 0));
    }

    private static ObservableGameStateSnapshot gameState() {
        var evidence = GroundedTestFixtures.playerEvidence();
        InstalledModMetadata farmersDelight = new InstalledModMetadata(
                "farmersdelight", "Farmer's Delight", "26.2-test", "Cooking expansion",
                List.of("vectorwing"), List.of("MIT"), Map.of(), "client_server", List.of("minecraft"));
        return new ObservableGameStateSnapshot(
                Instant.EPOCH,
                new ObservableGameStateSnapshot.RuntimeState(
                        "26.2", "fabric", false, "singleplayer", evidence, List.of()),
                new ObservableGameStateSnapshot.ModsState(
                        List.of(farmersDelight), evidence, List.of()),
                new ObservableGameStateSnapshot.OptionsState(
                        List.of(new ObservableGameStateSnapshot.OptionValue(
                                "video", "render_distance", "Render Distance", "16")),
                        evidence, List.of()),
                new ObservableGameStateSnapshot.PacksState(
                        List.of(new ObservableGameStateSnapshot.PackInfo(
                                "vanilla", "Default", "Minecraft resources", true, true,
                                "compatible", "builtin")),
                        List.of("vanilla"), evidence, List.of()),
                new ObservableGameStateSnapshot.ShaderState(
                        false, "none", "", Map.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.DiagnosticsState(
                        List.of(new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "coordinates", "1 64 2")), evidence, List.of()),
                new ObservableGameStateSnapshot.PlayerUiState(
                        GroundedTestFixtures.player(), "gameplay", "", evidence, List.of()),
                new ObservableGameStateSnapshot.WorldQueriesState(
                        Map.of("time", new ObservableGameStateSnapshot.QueryValue(
                                "time", "0", true, "server_authoritative")), evidence, List.of()));
    }

    private static final class TestPlatform implements PlatformService {
        @Override public String platformName() { return "fabric"; }
        @Override public String gameVersion() { return "26.2"; }
        @Override public boolean isModLoaded(String id) { return id.equals("farmersdelight"); }
        @Override public boolean isDevelopmentEnvironment() { return false; }
        @Override public List<InstalledModMetadata> installedMods() { return List.of(); }
        @Override public ModResourceSnapshot captureModResources() {
            return ModResourceSnapshot.unavailable(Instant.EPOCH, "e2e_fixture");
        }
    }

    private record Fixture(
            ResourceRequestRegistry resources,
            ResourceRequestRegistry.RequestHandle handle,
            LocalAgentToolExecutor executor,
            ToolInvocationContext context,
            long connectionGeneration) implements AutoCloseable {
        AgentToolResult execute(String alias, String arguments) {
            return execute(alias, JsonParser.parseString(arguments).getAsJsonObject());
        }

        AgentToolResult execute(String alias, JsonObject arguments) {
            return executor.execute(alias, arguments, context, new CancellationSignal()).join();
        }

        String itemPaths(int count) {
            StringBuilder paths = new StringBuilder("[");
            for (int index = 0; index < count; index++) {
                if (index > 0) paths.append(',');
                paths.append('"').append("/item/farmersdelight/food_").append(index).append('"');
            }
            return paths.append(']').toString();
        }

        dev.openallay.resource.result.ResourceResultStore.Scope resultScope() {
            return new dev.openallay.resource.result.ResourceResultStore.Scope(
                    GroundedTestFixtures.PLAYER_ID, "e2e", connectionGeneration);
        }

        @Override public void close() {
            handle.close();
            resources.close();
        }
    }
}
