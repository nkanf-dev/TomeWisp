package dev.openallay.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.model.CancellationSignal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RhinoJavascriptRuntimeTest {
    @Test
    void transformsDetachedMinecraftDataWithNormalJavascript() {
        RhinoJavascriptRuntime runtime = new RhinoJavascriptRuntime();

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
                Map.of("items", JsonParser.parseString("""
                        [
                          {"id":"minecraft:iron_sword","damage":6,"tags":["minecraft:swords"]},
                          {"id":"example:obsidian_sword","damage":12,"tags":["minecraft:swords"]},
                          {"id":"minecraft:apple","damage":0,"tags":[]}
                        ]
                        """)),
                Map.of(),
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
        Map<String, com.google.gson.JsonElement> values =
                Map.of("r_1", JsonParser.parseString("[3,8,5]"));

        JavascriptExecution execution = new RhinoJavascriptRuntime().execute(
                "return helpers.maxBy(workspace.open('r_1'), value => value);",
                Map.of(),
                values,
                new CancellationSignal());

        assertEquals(8, execution.value().getAsInt());
        JavascriptExecutionException unavailable = assertThrows(
                JavascriptExecutionException.class,
                () -> new RhinoJavascriptRuntime().execute(
                        "return workspace.open('r_other');",
                        Map.of(),
                        values,
                        new CancellationSignal()));
        assertEquals("workspace_handle_unavailable", unavailable.code());
        assertTrue(unavailable.getMessage().contains("unavailable"));
    }

    @Test
    void safeScopeDoesNotExposeJavaOrHostFacilities() {
        JavascriptExecution execution = new RhinoJavascriptRuntime().execute(
                """
                return {
                  packages: typeof Packages,
                  java: typeof Java,
                  adapter: typeof JavaAdapter,
                  load: typeof load,
                  quit: typeof quit
                };
                """,
                Map.of(),
                Map.of(),
                new CancellationSignal());

        execution.value()
                .getAsJsonObject()
                .entrySet()
                .forEach(entry -> assertEquals("undefined", entry.getValue().getAsString()));
    }

    @Test
    void rejectsCyclesAndNonFiniteNumbers() {
        RhinoJavascriptRuntime runtime = new RhinoJavascriptRuntime();
        JavascriptExecutionException cycle = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "const value = {}; value.self = value; return value;",
                        Map.of(),
                        Map.of(),
                        new CancellationSignal()));
        assertEquals("javascript_result_invalid", cycle.code());

        JavascriptExecutionException infinity = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "return Infinity;",
                        Map.of(),
                        Map.of(),
                        new CancellationSignal()));
        assertEquals("javascript_result_invalid", infinity.code());
    }

    @Test
    void stopsInfiniteScriptsAtDeadline() {
        RhinoJavascriptRuntime runtime =
                new RhinoJavascriptRuntime(Duration.ofMillis(25));

        JavascriptExecutionException timeout = assertThrows(
                JavascriptExecutionException.class,
                () -> runtime.execute(
                        "while (true) {}",
                        Map.of(),
                        Map.of(),
                        new CancellationSignal()));

        assertEquals("javascript_timeout", timeout.code());
    }

    @Test
    void rejectsOversizedSourceAndResultsBeforeTheyEnterAWorkspace() {
        JavascriptRuntimeLimits limits =
                new JavascriptRuntimeLimits(32, 4, 12, 8, 4, 16);
        RhinoJavascriptRuntime runtime =
                new RhinoJavascriptRuntime(Duration.ofSeconds(1), limits);

        assertEquals(
                "javascript_source_too_large",
                assertThrows(
                                JavascriptExecutionException.class,
                                () -> runtime.execute(
                                        "return '" + "x".repeat(40) + "';",
                                        Map.of(),
                                        Map.of(),
                                        new CancellationSignal()))
                        .code());
        assertEquals(
                "javascript_result_budget_exceeded",
                assertThrows(
                                JavascriptExecutionException.class,
                                () -> runtime.execute(
                                        "return [1,2,3,4,5,6,7,8,9];",
                                        Map.of(),
                                        Map.of(),
                                        new CancellationSignal()))
                        .code());
        assertEquals(
                "javascript_result_budget_exceeded",
                assertThrows(
                                JavascriptExecutionException.class,
                                () -> runtime.execute(
                                        "return 'abcdefghijklmnopq';",
                                        Map.of(),
                                        Map.of(),
                        new CancellationSignal()))
                        .code());
    }

    @Test
    void readsRecordComponentsWithoutStringifyingOrSerializingTheInput() {
        record DirectValue(String id, List<Integer> values) {
            @Override
            public String toString() {
                throw new AssertionError("The direct host path must not stringify snapshots");
            }
        }
        DirectValue direct = new DirectValue("direct", List.of(2, 4, 6));

        JavascriptExecution result = new RhinoJavascriptRuntime().execute(
                "return {id: mc.fixture.id, total: helpers.sum(mc.fixture.values)};",
                Map.of("fixture", direct),
                Map.of(),
                new CancellationSignal());

        assertEquals("direct", result.value().getAsJsonObject().get("id").getAsString());
        assertEquals(12, result.value().getAsJsonObject().get("total").getAsInt());
    }
}
