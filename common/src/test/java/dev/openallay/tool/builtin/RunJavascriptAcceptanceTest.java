package dev.openallay.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.script.RhinoJavascriptRuntime;
import dev.openallay.script.data.MinecraftAgentHostGraph;
import dev.openallay.script.extension.JavascriptDataModule;
import dev.openallay.script.extension.JavascriptDataModuleRegistry;
import dev.openallay.script.workspace.AgentResultWorkspaceRegistry;
import dev.openallay.script.workspace.JavascriptResultPresenter;
import dev.openallay.testing.JavascriptAgentTestFixtures;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic acceptance for the three product-level JavaScript analysis tasks. */
final class RunJavascriptAcceptanceTest {
    private record DirectModule(int value) {}

    private final AgentResultWorkspaceRegistry workspaces =
            new AgentResultWorkspaceRegistry();
    private final RunJavascriptTool tool = new RunJavascriptTool(
            new RhinoJavascriptRuntime(),
            MinecraftAgentHostGraph::new,
            workspaces,
            new JavascriptResultPresenter());
    private final ToolInvocationContext context =
            JavascriptAgentTestFixtures.context("javascript-acceptance");

    @Test
    void findsHighestDamageSwordInOneInvocation() {
        var result = invoke("""
                return mc.items
                  .filter(item => item.tags.includes("minecraft:swords"))
                  .map(item => ({
                    id: item.id,
                    damage: Number(item.properties["minecraft:attack_damage"])
                  }))
                  .filter(item => Number.isFinite(item.damage))
                  .sort((a, b) => b.damage - a.damage)
                  .slice(0, 1);
                """);

        assertEquals(JavascriptAgentTestFixtures.HIGHEST_DAMAGE_SWORD, result.getAsJsonArray()
                .get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(14, result.getAsJsonArray()
                .get(0).getAsJsonObject().get("damage").getAsInt());
    }

    @Test
    void findsStrongestPoisonItemAndItsProductionRecipeInOneInvocation() {
        var result = invoke("""
                const candidates = mc.items.flatMap(item =>
                  (item.properties["minecraft:effects"] ?? [])
                    .filter(effect => effect.id === "minecraft:poison")
                    .map(effect => ({
                      itemId: item.id,
                      amplifier: Number(effect.amplifier ?? 0),
                      duration: Number(effect.duration ?? 0)
                    })))
                  .sort((a, b) =>
                    (b.amplifier - a.amplifier) || (b.duration - a.duration));
                const best = candidates[0];
                return {
                  best,
                  recipes: mc.recipes
                    .filter(recipe => recipe.outputs.some(output =>
                      output.stack.itemId === best.itemId))
                    .map(recipe => ({
                      recipeId: recipe.id,
                      ingredients: recipe.ingredients.map(ingredient => ({
                        count: ingredient.count,
                        alternatives: ingredient.alternatives.map(value => value.id)
                      }))
                    }))
                };
                """);

        assertEquals(JavascriptAgentTestFixtures.STRONGEST_POISON_ITEM, result.getAsJsonObject()
                .getAsJsonObject("best").get("itemId").getAsString());
        assertEquals(JavascriptAgentTestFixtures.STRONGEST_POISON_ITEM, result.getAsJsonObject()
                .getAsJsonArray("recipes").get(0).getAsJsonObject()
                .get("recipeId").getAsString());
    }

    @Test
    void findsContainerRecipeWithFewestConsumedMaterialUnitsInOneInvocation() {
        var result = invoke("""
                const items = new Map(mc.items.map(item => [item.id, item]));
                return mc.recipes
                  .filter(recipe => recipe.outputs.some(output =>
                    items.get(output.stack.itemId)?.tags.includes("openallay:containers")))
                  .map(recipe => ({
                    recipeId: recipe.id,
                    output: recipe.outputs[0].stack.itemId,
                    materialUnits: recipe.ingredients
                      .filter(ingredient => ingredient.consumed)
                      .reduce((sum, ingredient) => sum + Number(ingredient.count), 0)
                  }))
                  .sort((a, b) => a.materialUnits - b.materialUnits)
                  .slice(0, 1);
                """);

        assertEquals(JavascriptAgentTestFixtures.LEAST_MATERIAL_CONTAINER, result.getAsJsonArray()
                .get(0).getAsJsonObject().get("output").getAsString());
        assertEquals(2, result.getAsJsonArray()
                .get(0).getAsJsonObject().get("materialUnits").getAsInt());
    }

    @Test
    void capturesOneDetachedProjectionPerRequestAcrossJavascriptCalls() {
        AtomicInteger captures = new AtomicInteger();
        JavascriptDataModuleRegistry extensions = new JavascriptDataModuleRegistry();
        extensions.register("test", List.of(new JavascriptDataModule() {
            @Override
            public String id() {
                return "test:counter";
            }

            @Override
            public Snapshot capture(ToolInvocationContext ignored) {
                captures.incrementAndGet();
                return new Snapshot(
                        new DirectModule(1),
                        List.of(JavascriptAgentTestFixtures.context("evidence")
                                .registries()
                                .orElseThrow()
                                .evidence()));
            }
        }));
        RunJavascriptTool cached = new RunJavascriptTool(
                new RhinoJavascriptRuntime(),
                invocation -> new MinecraftAgentHostGraph(
                        invocation,
                        dev.openallay.knowledge.KnowledgeSnapshot::empty,
                        extensions),
                new AgentResultWorkspaceRegistry(),
                new JavascriptResultPresenter());

        assertInstanceOf(
                ToolResult.Success.class,
                cached.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return mc.extensions['test:counter'];", List.of()),
                                new CancellationSignal())
                        .join());
        assertInstanceOf(
                ToolResult.Success.class,
                cached.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return mc.extensions['test:counter'];", List.of()),
                                new CancellationSignal())
                        .join());

        assertEquals(1, captures.get());
        cached.closeRequestScope(context.correlationId());
        assertInstanceOf(
                ToolResult.Success.class,
                cached.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return mc.extensions['test:counter'];", List.of()),
                                new CancellationSignal())
                        .join());
        assertEquals(2, captures.get());
    }

    @Test
    void unsupportedExtensionValueDoesNotHideIndependentDirectRecordModule() {
        JavascriptDataModuleRegistry extensions = new JavascriptDataModuleRegistry();
        extensions.register("test", List.of(
                module("test:good", new DirectModule(7)),
                module("test:unsupported", new Object())));
        RunJavascriptTool isolated = new RunJavascriptTool(
                new RhinoJavascriptRuntime(),
                invocation -> new MinecraftAgentHostGraph(
                        invocation,
                        dev.openallay.knowledge.KnowledgeSnapshot::empty,
                        extensions),
                new AgentResultWorkspaceRegistry(),
                new JavascriptResultPresenter());

        ToolResult.Success<RunJavascriptTool.Output> success = assertInstanceOf(
                ToolResult.Success.class,
                isolated.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return mc.extensions['test:good'].value;",
                                        List.of(),
                                        List.of("extensions")),
                                new CancellationSignal())
                        .join());
        assertEquals(7, success.value().preview().getAsInt());

        ToolResult.Failure<RunJavascriptTool.Output> unsupported = assertInstanceOf(
                ToolResult.Failure.class,
                isolated.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return mc.extensions['test:unsupported'];",
                                        List.of(),
                                        List.of("extensions")),
                                new CancellationSignal())
                        .join());
        assertEquals("javascript_host_type_unsupported", unsupported.code());
    }

    @Test
    void exposesOnlyExplicitlySelectedMinecraftRootsToRhino() {
        ToolResult.Success<RunJavascriptTool.Output> success = assertInstanceOf(
                ToolResult.Success.class,
                tool.invokeAsync(
                                context,
                                new RunJavascriptTool.Input(
                                        "return {items: mc.items.length, recipes: typeof mc.recipes};",
                                        List.of(),
                                        List.of("items")),
                                new CancellationSignal())
                        .join());
        var canonical = workspaces
                .open(context.correlationId())
                .open(success.value().handle())
                .getAsJsonObject();

        assertTrue(canonical.get("items").getAsInt() > 0);
        assertEquals("undefined", canonical.get("recipes").getAsString());
    }

    private com.google.gson.JsonElement invoke(String source) {
        ToolResult<RunJavascriptTool.Output> raw = tool.invokeAsync(
                        context,
                        new RunJavascriptTool.Input(source, List.of()),
                        new CancellationSignal())
                .join();
        ToolResult.Success<RunJavascriptTool.Output> success =
                assertInstanceOf(ToolResult.Success.class, raw);
        assertTrue(success.value().evidence().size() >= 2);
        return workspaces.open(context.correlationId()).open(success.value().handle());
    }

    private static JavascriptDataModule module(String id, Object value) {
        return new JavascriptDataModule() {
            @Override public String id() { return id; }

            @Override
            public Snapshot capture(ToolInvocationContext ignored) {
                return new Snapshot(
                        value,
                        List.of(JavascriptAgentTestFixtures.context("module-evidence")
                                .registries().orElseThrow().evidence()));
            }
        };
    }

}
