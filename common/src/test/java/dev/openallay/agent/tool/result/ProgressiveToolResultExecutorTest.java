package dev.openallay.agent.tool.result;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ProgressiveToolResultExecutorTest {
    @Test
    void turnsLargeInternalJsonIntoShortReadableTextAndPagesTheExactResult() {
        JsonObject exact = new JsonObject();
        exact.addProperty("status", "success");
        JsonObject value = new JsonObject();
        JsonArray rows = new JsonArray();
        for (int index = 0; index < 3000; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("id", "farmersdelight:food_" + index);
            row.addProperty("nutrition", index % 20);
            rows.add(row);
        }
        value.add("rows", rows);
        exact.add("value", value);

        ToolInvocationContext context = ToolInvocationContext.developmentConsole("request-a");
        ProgressiveToolResultExecutor executor = new ProgressiveToolResultExecutor(
                new FixedExecutor(exact), new ToolResultResourceStore());
        AgentToolResult projected = executor.execute(
                        "test_large", new JsonObject(), context, new CancellationSignal())
                .join();

        assertTrue(projected.modelPayload().toString().getBytes(StandardCharsets.UTF_8).length
                <= ProgressiveToolResultExecutor.PROJECTION_BYTES);
        assertTrue(projected.modelPayload().has("content"));
        assertFalse(projected.modelPayload().get("content").getAsString().contains("{"));
        assertTrue(projected.normalized().has("value"),
                "the UI projection remains structured JSON derived from the exact result");
        assertTrue(projected.normalized().toString().getBytes(StandardCharsets.UTF_8).length
                <= ProgressiveToolResultExecutor.UI_PROJECTION_BYTES);
        String ref = projected.modelPayload().get("resultRef").getAsString();

        JsonObject read = new JsonObject();
        read.addProperty("resultRef", ref);
        read.addProperty("action", "search");
        read.addProperty("query", "food_2999");
        AgentToolResult page = executor.execute(
                        ProgressiveToolResultExecutor.MODEL_NAME,
                        read,
                        context,
                        new CancellationSignal())
                .join();
        assertFalse(page.failure());
        assertTrue(page.modelPayload().get("content").getAsString().contains("food_2999"));
    }

    @Test
    void requestOwnershipPreventsAnotherRequestFromReadingTheReference() {
        JsonObject exact = new JsonObject();
        exact.addProperty("status", "success");
        exact.addProperty("value", "x".repeat(20_000));
        ProgressiveToolResultExecutor executor = new ProgressiveToolResultExecutor(
                new FixedExecutor(exact), new ToolResultResourceStore());
        AgentToolResult projected = executor.execute(
                        "test_large", new JsonObject(),
                        ToolInvocationContext.developmentConsole("request-a"),
                        new CancellationSignal())
                .join();
        JsonObject read = new JsonObject();
        read.addProperty("resultRef", projected.modelPayload().get("resultRef").getAsString());
        read.addProperty("action", "read");
        AgentToolResult denied = executor.execute(
                        ProgressiveToolResultExecutor.MODEL_NAME,
                        read,
                        ToolInvocationContext.developmentConsole("request-b"),
                        new CancellationSignal())
                .join();
        assertTrue(denied.failure());
        assertEquals("resource_not_found", denied.normalized().get("code").getAsString());
    }

    private record FixedExecutor(JsonObject result) implements AgentToolExecutor {
        @Override public List<ModelToolDefinition> definitions() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            return List.of(new ModelToolDefinition("test_large", "large fixture", schema));
        }
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override public CompletableFuture<AgentToolResult> execute(
                String name, JsonObject arguments, ToolInvocationContext context,
                CancellationSignal cancellation) {
            return CompletableFuture.completedFuture(
                    new AgentToolResult("test:large", result, false));
        }
    }
}
