package dev.openallay.agent.tool;

import com.google.gson.JsonObject;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface AgentToolExecutor {
    String UNKNOWN_TOOL_ID = "openallay:unknown";

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

    /** Releases resources owned by one terminal Agent request. */
    default void closeRequestScope(String correlationId) {}
}
