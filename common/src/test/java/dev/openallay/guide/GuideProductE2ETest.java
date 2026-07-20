package dev.openallay.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.capability.CapabilitySettingsCatalog;
import dev.openallay.client.ClientGuideRuntime;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import dev.openallay.platform.PlatformService;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.builtin.CalculateCraftabilityTool;
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
        OpenAllayRuntime base = runtime(tools);
        ScriptedModel model = new ScriptedModel(List.of(
                toolWithText("1", "准备查询配方。", "resource_grep", vfsGrep(
                        "/recipe", "minecraft:iron_block")),
                toolWithText("2", "我再确认完整配方。", "resource_read", vfsRead(
                        "/recipe/minecraft/iron_block")),
                toolWithText("3", "现在检查你的库存。", "resource_read", vfsRead(
                        "/player/inventory")),
                toolWithText("4", "最后计算材料缺口。", "openallay__calculate_craftability", json(
                        "sourceId", "minecraft:recipe_manager",
                        "generation", GroundedTestFixtures.RECIPE_GENERATION,
                        "recipeId", "minecraft:iron_block",
                        "crafts", 1)),
                text("你有 4 个铁锭；制作 1 个铁块还缺 5 个。")));
        ClientGuideRuntime local = new ClientGuideRuntime(
                base, model, new AgentSessionStore(), gson, Runnable::run,
                new ContextBudget(100_000, 4_096), "fixture:vfs-e2e");
        GuideRemoteEndpoint noServer = new GuideRemoteEndpoint() {
            @Override public boolean serverModelAvailable() { return false; }
            @Override public boolean serverToolsAvailable() { return false; }
            @Override public boolean ask(UUID id, String session, String question,
                    Consumer<dev.openallay.agent.AgentEvent> events) { return false; }
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
        assertEquals(
                dev.openallay.resource.vfs.ResourcePresentation.Kind.RECIPE,
                completed.tools().get(1).uiReference().presentationKind());
        assertFalse(completed.sources().isEmpty());
        assertEquals("你有 4 个铁锭；制作 1 个铁块还缺 5 个。", completed.assistantText());
        assertEquals(5, model.requests.size());
        assertTrue(model.requests.get(4).messages().getLast().content().stream()
                .filter(ModelContent.ToolResult.class::isInstance)
                .map(ModelContent.ToolResult.class::cast)
                .anyMatch(result -> result.value().toString().contains("missing: 5")));
    }

    private static OpenAllayRuntime runtime(ToolRegistry tools) {
        PlatformService platform = new PlatformService() {
                    @Override public String platformName() { return "common-test"; }
                    @Override public String gameVersion() { return "26.2"; }
                    @Override public boolean isModLoaded(String id) { return false; }
                    @Override public boolean isDevelopmentEnvironment() { return true; }
                };
        KnowledgeRegistry knowledge = new KnowledgeRegistry();
        ResourceRequestRegistry resources = new ResourceRequestRegistry(platform, knowledge);
        tools.registerResourceTools("e2e:vfs", resources);
        tools.register("e2e:craftability", List.of(new CalculateCraftabilityTool()));
        SkillRepository skills = new SkillRepository(new SkillParser(), tools.descriptors().stream()
                .map(value -> value.id()).toList());
        return new OpenAllayRuntime(
                platform,
                tools,
                knowledge,
                new PatchouliMultiblockStore(),
                skills,
                new DevelopmentToolInspector(tools),
                null,
                new CapabilitySettingsCatalog(),
                resources);
    }

    private static JsonObject vfsRead(String path) {
        JsonObject input = new JsonObject();
        com.google.gson.JsonArray paths = new com.google.gson.JsonArray();
        paths.add(path);
        input.add("paths", paths);
        return input;
    }

    private static JsonObject vfsGrep(String root, String pattern) {
        JsonObject search = new JsonObject();
        com.google.gson.JsonArray roots = new com.google.gson.JsonArray();
        roots.add(root);
        search.add("roots", roots);
        search.addProperty("pattern", pattern);
        search.addProperty("mode", "LITERAL");
        com.google.gson.JsonArray searches = new com.google.gson.JsonArray();
        searches.add(search);
        JsonObject input = new JsonObject();
        input.add("searches", searches);
        return input;
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
