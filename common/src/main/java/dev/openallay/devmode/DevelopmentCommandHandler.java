package dev.openallay.devmode;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.ToolResult;
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
        return invoke(ToolInvocationContext.developmentConsole("dev:" + id), id);
    }

    public String invoke(ToolInvocationContext context, String id) {
        return switch (inspector.invokeNoArgument(context, id)) {
            case ToolResult.Success<?> success -> "SUCCESS " + success.value();
            case ToolResult.Failure<?> failure ->
                "FAILURE " + failure.code() + ": " + failure.message();
        };
    }
}
