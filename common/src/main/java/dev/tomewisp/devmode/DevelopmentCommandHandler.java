package dev.tomewisp.devmode;

import dev.tomewisp.tool.ToolResult;
import java.util.List;

public final class DevelopmentCommandHandler {
    private final DevelopmentToolInspector inspector;

    public DevelopmentCommandHandler(DevelopmentToolInspector inspector) {
        this.inspector = inspector;
    }

    public List<String> listTools() {
        return inspector.listTools();
    }

    public String invoke(String id) {
        return switch (inspector.invokeNoArgument(id)) {
            case ToolResult.Success<?> success -> "SUCCESS " + success.value();
            case ToolResult.Failure<?> failure ->
                "FAILURE " + failure.code() + ": " + failure.message();
        };
    }
}
