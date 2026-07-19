package dev.openallay.devmode;

import dev.openallay.context.ToolInvocationContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
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
            public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output("pong"));
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(tool));
        DevelopmentToolInspector inspector = new DevelopmentToolInspector(registry);

        assertEquals(List.of("test:ping - Ping"), inspector.listTools());
        assertInstanceOf(
                ToolResult.Success.class,
                inspector.invokeNoArgument(
                        ToolInvocationContext.developmentConsole("test:ping"), "test:ping"));
    }
}
