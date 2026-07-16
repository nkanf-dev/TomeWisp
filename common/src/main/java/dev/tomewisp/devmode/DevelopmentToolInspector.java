package dev.tomewisp.devmode;

import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
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

    public ToolResult<?> invokeNoArgument(String id) {
        Tool<?, ?> raw = registry.find(id).orElse(null);
        if (raw == null) {
            return new ToolResult.Failure<>("unknown_tool", "Unknown tool: " + id);
        }
        try {
            Object input = raw.descriptor().inputType().getDeclaredConstructor().newInstance();
            return invokeUnchecked(raw, input);
        } catch (ReflectiveOperationException exception) {
            return new ToolResult.Failure<>(
                    "input_required", "Tool requires explicit input: " + id);
        }
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<?> invokeUnchecked(Tool<?, ?> raw, Object input) {
        return ((Tool<Object, Object>) raw).invoke(input);
    }
}
