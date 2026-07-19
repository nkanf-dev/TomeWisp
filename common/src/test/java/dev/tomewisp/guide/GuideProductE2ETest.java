package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.guide.ui.GuideRecipePresenter;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.builtin.CalculateCraftabilityTool;
import dev.tomewisp.tool.builtin.GetRecipeTool;
import dev.tomewisp.tool.builtin.InspectInventoryTool;
import dev.tomewisp.tool.builtin.SearchRecipesTool;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideProductE2ETest {
    @Test
    void realAgentServiceAndGroundedToolsCompleteLongCraftabilityTrace() {
        Gson gson = new Gson();
        ToolRegistry tools = new ToolRegistry();
        tools.register("e2e", List.of(
                new SearchRecipesTool(),
                new GetRecipeTool(),
                new InspectInventoryTool(),
                new CalculateCraftabilityTool()));
        TomeWispRuntime base = runtime(tools);
        ScriptedModel model = new ScriptedModel(List.of(
                toolWithText("1", "准备查询配方。", "tomewisp__search_recipes", json(
                        "outputItem", "minecraft:iron_block")),
                toolWithText("2", "我再确认完整配方。", "tomewisp__get_recipe", json(
                        "sourceId", "minecraft:recipe_manager",
                        "generation", GroundedTestFixtures.RECIPE_GENERATION,
                        "recipeId", "minecraft:iron_block")),
                toolWithText("3", "现在检查你的库存。", "tomewisp__inspect_inventory", new JsonObject()),
                toolWithText("4", "最后计算材料缺口。", "tomewisp__calculate_craftability", json(
                        "sourceId", "minecraft:recipe_manager",
                        "generation", GroundedTestFixtures.RECIPE_GENERATION,
                        "recipeId", "minecraft:iron_block",
                        "crafts", 1)),
                text("你有 4 个铁锭；制作 1 个铁块还缺 5 个。")));
        ClientGuideRuntime local = new ClientGuideRuntime(
                base, model, new AgentSessionStore(), gson, Runnable::run);
        GuideRemoteEndpoint noServer = new GuideRemoteEndpoint() {
            @Override public boolean serverModelAvailable() { return false; }
            @Override public boolean serverToolsAvailable() { return false; }
            @Override public boolean ask(UUID id, String session, String question,
                    Consumer<dev.tomewisp.agent.AgentEvent> events) { return false; }
            @Override public boolean cancel(UUID id) { return false; }
            @Override public void disconnect() {}
        };
        GuideService service = new GuideService(
                GroundedTestFixtures.PLAYER_ID,
                local,
                noServer,
                (capabilities, correlation) -> new ToolResult.Success<>(
                        GroundedTestFixtures.fullContext()),
                Runnable::run,
                Clock.systemUTC(),
                gson);

        UUID request = ((ToolResult.Success<UUID>) assertInstanceOf(
                ToolResult.Success.class, service.ask("我能做铁块吗？").join())).value();
        GuideRequestSnapshot completed = service.snapshot().sessions().getFirst().requests().stream()
                .filter(value -> value.requestId().equals(request))
                .findFirst().orElseThrow();

        assertEquals(GuideRequestStatus.COMPLETED, completed.status());
        assertEquals(4, completed.tools().size());
        assertEquals(
                List.of(
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class),
                completed.timeline().stream().map(Object::getClass).toList());
        assertTrue(completed.tools().stream().allMatch(
                value -> value.status() == GuideToolStatus.SUCCEEDED));
        JsonObject recipeSearch = completed.tools().getFirst().normalized();
        assertTrue(recipeSearch.getAsJsonObject("value").has("catalog"));
        assertEquals(1, GuideRecipePresenter.cards(
                completed.tools().getFirst().toolId(), recipeSearch).size());
        assertFalse(completed.sources().isEmpty());
        assertEquals("你有 4 个铁锭；制作 1 个铁块还缺 5 个。", completed.assistantText());
        assertEquals(5, model.requests.size());
        assertTrue(model.requests.get(4).messages().getLast().content().stream()
                .filter(ModelContent.ToolResult.class::isInstance)
                .map(ModelContent.ToolResult.class::cast)
                .anyMatch(result -> result.value().toString().contains("\"missing\":5")));
    }

    private static TomeWispRuntime runtime(ToolRegistry tools) {
        return new TomeWispRuntime(
                new PlatformService() {
                    @Override public String platformName() { return "common-test"; }
                    @Override public String gameVersion() { return "test"; }
                    @Override public boolean isModLoaded(String id) { return false; }
                    @Override public boolean isDevelopmentEnvironment() { return true; }
                },
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), tools.descriptors().stream()
                        .map(value -> value.id()).toList()),
                new DevelopmentToolInspector(tools),
                null);
    }

    private static ModelTurn tool(String id, String name, JsonObject arguments) {
        return new ModelTurn("fixture", "scripted", List.of(
                new ModelContent.ToolUse(id, name, arguments)), "tool_use", ModelUsage.empty());
    }

    private static ModelTurn toolWithText(
            String id, String text, String name, JsonObject arguments) {
        return new ModelTurn("fixture", "scripted", List.of(
                new ModelContent.Text(text),
                new ModelContent.ToolUse(id, name, arguments)), "tool_use", ModelUsage.empty());
    }

    private static ModelTurn text(String value) {
        return new ModelTurn("fixture", "scripted", List.of(
                new ModelContent.Text(value)), "end_turn", new ModelUsage(100, 20, 0));
    }

    private static JsonObject json(Object... values) {
        JsonObject object = new JsonObject();
        for (int index = 0; index < values.length; index += 2) {
            Object value = values[index + 1];
            if (value instanceof Number number) {
                object.addProperty((String) values[index], number);
            } else {
                object.addProperty((String) values[index], (String) value);
            }
        }
        return object;
    }

    private static final class ScriptedModel implements ModelClient {
        private final ArrayDeque<ModelTurn> turns;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ScriptedModel(List<ModelTurn> turns) {
            this.turns = new ArrayDeque<>(turns);
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            ModelTurn turn = turns.removeFirst();
            if (!turn.text().isBlank()) {
                events.accept(new ModelEvent.TextDelta(turn.text()));
            }
            if (turn.toolUses().isEmpty()) {
                events.accept(new ModelEvent.UsageUpdate(turn.usage()));
            }
            return CompletableFuture.completedFuture(turn);
        }
    }
}
