package dev.openallay.devmode;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.List;

public final class DevelopmentToolInspector {
    private final ToolRegistry registry;

    public DevelopmentToolInspector(ToolRegistry registry) {
        this.registry = registry;
    }

    public List<String> listTools() {
        return registry.descriptors().stream()
                .map(value -> value.id() + " - " + value.description())
                .toList();
    }

    public ToolResult<?> invokeNoArgument(ToolInvocationContext context, String id) {
        Tool<?, ?> raw = registry.find(id).orElse(null);
        if (raw == null) {
            return new ToolResult.Failure<>("unknown_tool", "Unknown tool: " + id);
        }
        try {
            Object input = raw.descriptor().inputType().getDeclaredConstructor().newInstance();
            return invokeUnchecked(raw, context, input);
        } catch (ReflectiveOperationException exception) {
            return new ToolResult.Failure<>(
                    "input_required", "Tool requires explicit input: " + id);
        }
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<?> invokeUnchecked(
            Tool<?, ?> raw, ToolInvocationContext context, Object input) {
        return ((Tool<Object, Object>) raw).invoke(context, input);
    }
}
