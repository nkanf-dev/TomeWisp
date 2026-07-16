package dev.tomewisp.devmode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DevelopmentToolInspectorTest {
    record Input() {}

    record Output(String value) {}

    @Test
    void listsAndInvokesNoArgumentTool() {
        Tool<Input, Output> tool = new Tool<>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:ping", "Ping", Input.class, Output.class, ToolAccess.READ_ONLY);

            @Override
            public ToolDescriptor<Input, Output> descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Output> invoke(Input input) {
                return new ToolResult.Success<>(new Output("pong"));
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(tool));
        DevelopmentToolInspector inspector = new DevelopmentToolInspector(registry);

        assertEquals(List.of("test:ping - Ping"), inspector.listTools());
        assertInstanceOf(
                ToolResult.Success.class, inspector.invokeNoArgument("test:ping"));
    }
}
