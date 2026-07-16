package dev.tomewisp.agent.tool;

import com.google.gson.JsonObject;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface AgentToolExecutor {
    List<ModelToolDefinition> definitions();

    Set<ContextCapability> requiredContext();

    CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation);
}
