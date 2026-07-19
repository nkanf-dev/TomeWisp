package dev.openallay.guide;

import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.ToolResult;
import java.util.Set;

@FunctionalInterface
public interface GuideContextProvider {
    ToolResult<ToolInvocationContext> capture(
            Set<ContextCapability> capabilities, String correlationId);

    default ToolResult<Integer> refreshKnowledge() {
        return new ToolResult.Success<>(0);
    }
}
