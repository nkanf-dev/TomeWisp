package dev.tomewisp.agent.tool;

import com.google.gson.JsonObject;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface AgentToolExecutor {
    String UNKNOWN_TOOL_ID = "tomewisp:unknown";

    List<ModelToolDefinition> definitions();

    Set<ContextCapability> requiredContext();

    /**
     * Resolves one provider-returned name to the canonical registered Tool ID.
     * Implementations that accept aliases override this method; the default keeps
     * existing exact-name executors source-compatible.
     */
    default Optional<String> canonicalToolId(String modelToolName) {
        if (modelToolName == null || modelToolName.isBlank()) {
            return Optional.empty();
        }
        return definitions().stream()
                .anyMatch(definition -> definition.name().equals(modelToolName))
                ? Optional.of(modelToolName)
                : Optional.empty();
    }

    CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation);
}
