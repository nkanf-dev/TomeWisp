package dev.tomewisp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompositeAgentToolExecutorTest {
    @Test
    void routesEncodedAndRegisteredCanonicalAliasesToOneCanonicalTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new FactTool()));
        CompositeAgentToolExecutor executor = new CompositeAgentToolExecutor(
                List.of(new LocalAgentToolExecutor(registry, new Gson())));

        assertEquals("test:fact", executor.canonicalToolId("test__fact").orElseThrow());
        assertEquals("test:fact", executor.canonicalToolId("test:fact").orElseThrow());
        AgentToolResult result = executor.execute(
                        "test:fact",
                        JsonParser.parseString("{\"value\":7}").getAsJsonObject(),
                        ToolInvocationContext.developmentConsole("alias-test"),
                        new CancellationSignal())
                .join();

        assertFalse(result.failure());
        assertEquals("test:fact", result.toolId());
        assertEquals(7, result.normalized().getAsJsonObject("value").get("value").getAsInt());
    }

    @Test
    void returnsACompleteFailureForAnUnknownNameInsteadOfThrowing() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new FactTool()));
        CompositeAgentToolExecutor executor = new CompositeAgentToolExecutor(
                List.of(new LocalAgentToolExecutor(registry, new Gson())));

        AgentToolResult result = executor.execute(
                        "tomewisp:invented",
                        JsonParser.parseString("{}").getAsJsonObject(),
                        ToolInvocationContext.developmentConsole("unknown-test"),
                        new CancellationSignal())
                .join();

        assertTrue(result.failure());
        assertEquals(AgentToolExecutor.UNKNOWN_TOOL_ID, result.toolId());
        assertEquals("failure", result.normalized().get("status").getAsString());
        assertEquals("tool_unavailable", result.normalized().get("code").getAsString());
    }

    private static final class FactTool implements Tool<FactTool.Input, FactTool.Output> {
        record Input(int value) {}
        record Output(int value) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "test:fact", "Return a fact", Input.class, Output.class, ToolAccess.READ_ONLY);

        @Override
        public ToolDescriptor<Input, Output> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output(input.value()));
        }
    }
}
