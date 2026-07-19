package dev.openallay.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.ToolInvocationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolRegistryTest {
    record Input() {}

    record Output(String value) {}

    @Test
    void ordersIdsAndRejectsDuplicates() {
        ToolRegistry registry = new ToolRegistry();
        Tool<Input, Output> beta = tool("test:beta");
        Tool<Input, Output> alpha = tool("test:alpha");
        registry.register("provider-a", List.of(beta, alpha));

        assertEquals(
                List.of("test:alpha", "test:beta"),
                registry.descriptors().stream().map(ToolDescriptor::id).toList());
        assertThrows(
                IllegalStateException.class,
                () -> registry.register("provider-b", List.of(alpha)));
        assertEquals(alpha, registry.find("test:alpha").orElseThrow());
        assertEquals(
                List.of("provider-a", "provider-a"),
                registry.registrations().stream().map(RegisteredTool::providerId).toList());
        assertEquals(
                List.of("test:alpha", "test:beta"),
                registry.registrations().stream()
                        .map(registered -> registered.tool().descriptor().id())
                        .toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.registrations().add(registry.registrations().getFirst()));
    }

    private static Tool<Input, Output> tool(String id) {
        return new Tool<>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    id, "Test tool", Input.class, Output.class, ToolAccess.READ_ONLY);

            @Override
            public ToolDescriptor<Input, Output> descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output(id));
            }
        };
    }
}
