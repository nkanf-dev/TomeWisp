package dev.tomewisp.guide;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.util.Set;

@FunctionalInterface
public interface GuideContextProvider {
    ToolResult<ToolInvocationContext> capture(
            Set<ContextCapability> capabilities, String correlationId);

    default ToolResult<Integer> refreshKnowledge() {
        return new ToolResult.Success<>(0);
    }
}
