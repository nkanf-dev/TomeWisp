package dev.tomewisp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LocalAgentToolExecutorTest {
    record Input(int value) {}
    record Output(int fact) {}

    @Test
    void exposesSchemasAndInvokesTheRealRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new Tool<Input, Output>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:fact",
                    "Return a fact",
                    Input.class,
                    Output.class,
                    ToolAccess.READ_ONLY,
                    Set.of(ContextCapability.RECIPES));

            @Override public ToolDescriptor<Input, Output> descriptor() { return descriptor; }
            @Override public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output(input.value()));
            }
        }));
        LocalAgentToolExecutor executor = new LocalAgentToolExecutor(registry, new Gson());

        assertEquals(Set.of(ContextCapability.RECIPES), executor.requiredContext());
        assertEquals("test__fact", executor.definitions().getFirst().name());
        AgentToolResult result = executor.execute(
                        "test__fact",
                        JsonParser.parseString("{\"value\":42}").getAsJsonObject(),
                        ToolInvocationContext.developmentConsole("test"),
                        new CancellationSignal())
                .join();
        assertFalse(result.failure());
        assertEquals(42, result.normalized().getAsJsonObject("value").get("fact").getAsInt());
    }

    @Test
    void returnsNormalizedInvalidArguments() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new Tool<Input, Output>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:fact", "Return a fact", Input.class, Output.class, ToolAccess.READ_ONLY);
            @Override public ToolDescriptor<Input, Output> descriptor() { return descriptor; }
            @Override public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output(input.value()));
            }
        }));
        AgentToolResult result = new LocalAgentToolExecutor(registry, new Gson())
                .execute(
                        "test__fact",
                        JsonParser.parseString("{\"value\":{}}").getAsJsonObject(),
                        ToolInvocationContext.developmentConsole("test"),
                        new CancellationSignal())
                .join();
        assertTrue(result.failure());
        assertEquals("invalid_arguments", result.normalized().get("code").getAsString());
    }
}
