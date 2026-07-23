package dev.openallay.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.model.CancellationSignal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RhinoJavascriptRuntimeTest {
    private final Gson gson = new Gson();

    @Test
    void transformsDetachedMinecraftDataWithNormalJavascript() {
        RhinoJavascriptRuntime runtime = new RhinoJavascriptRuntime(gson);

        JavascriptExecution execution = runtime.execute(
                """
                const swords = mc.items
                  .filter(item => item.tags.includes("minecraft:swords"))
                  .map(item => ({id: item.id, damage: item.damage}))
                  .sort((a, b) => b.damage - a.damage);
                return {
                  strongest: swords[0],
                  totalDamage: swords.reduce((sum, item) => sum + item.damage, 0),
                  grouped: helpers.groupBy(swords, item => item.damage >= 10 ? "high" : "normal")
                };
                """,
                JsonParser.parseString("""
                        {"items":[
                          {"id":"minecraft:iron_sword","damage":6,"tags":["minecraft:swords"]},
                          {"id":"example:obsidian_sword","damage":12,"tags":["minecraft:swords"]},
                          {"id":"minecraft:apple","damage":0,"tags":[]}
                        ]}
                        """),
                new JsonObject(),
                new CancellationSignal());

        assertEquals(
                "example:obsidian_sword",
                execution.value()
                        .getAsJsonObject()
                        .getAsJsonObject("strongest")
                        .get("id")
                        .getAsString());
        assertEquals(
                18,
                execution.value().getAsJsonObject().get("totalDamage").getAsInt());
        assertEquals(
                1,
                execution.value()
                        .getAsJsonObject()
                        .getAsJsonObject("grouped")
                        .getAsJsonArray("high")
                        .size());
    }

    @Test
    void reopensOnlyExplicitWorkspaceValues() {
        JsonObject values = new JsonObject();
        values.add("r_1", JsonParser.parseString("[3,8,5]"));

        JavascriptExecution execution = new RhinoJavascriptRuntime(gson).execute(
                "return helpers.maxBy(workspace.open('r_1'), value => value);",
                new JsonObject(),
                values,
                new CancellationSignal());

        assertEquals(8, execution.value().getAsInt());
        JavascriptExecutionException unavailable = assertThrows(
                JavascriptExecutionException.class,
                () -> new RhinoJavascriptRuntime(gson).execute(
                        "return workspace.open('r_other');",
                        new JsonObject(),
                        values,
                        new CancellationSignal()));
        assertEquals("javascript_error", unavailable.code());
        assertTrue(unavailable.getMessage().contains("workspace_handle_unavailable"));
    }

    @Test
    void safeScopeDoesNotExposeJavaOrHostFacilities() {
        JavascriptExecution execution = new RhinoJavascriptRuntime(gson).execute(
                """
                return {
                  packages: typeof Packages,
                  java: typeof Java,
                  adapter: typeof JavaAdapter,
                  load: typeof load,
                  quit: typeof quit
                };
                """,
                new JsonObject(),
                new JsonObject(),
                new CancellationSignal());

        execution.value()
                .getAsJsonObject()
                .entrySet()
                .forEach(entry -> assertEquals("undefined", entry.getValue().getAsString()));
    }

    @Test
    void rejectsCyclesAndNonFiniteNumbers() {
        RhinoJavascriptRuntime runtime = new RhinoJavascriptRuntime(gson);
        JavascriptExecutionException cycle = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "const value = {}; value.self = value; return value;",
                        new JsonObject(),
                        new JsonObject(),
                        new CancellationSignal()));
        assertEquals("javascript_result_invalid", cycle.code());

        JavascriptExecutionException infinity = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "return Infinity;",
                        new JsonObject(),
                        new JsonObject(),
                        new CancellationSignal()));
        assertEquals("javascript_result_invalid", infinity.code());
    }

    @Test
    void stopsInfiniteScriptsAtDeadline() {
        RhinoJavascriptRuntime runtime =
                new RhinoJavascriptRuntime(gson, Duration.ofMillis(25));

        JavascriptExecutionException timeout = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "while (true) {}",
                        new JsonObject(),
                        new JsonObject(),
                        new CancellationSignal()));

        assertEquals("javascript_timeout", timeout.code());
    }
}

