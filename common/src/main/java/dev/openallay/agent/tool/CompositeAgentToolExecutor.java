package dev.openallay.agent.tool;

import com.google.gson.JsonObject;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class CompositeAgentToolExecutor implements AgentToolExecutor {
    private final List<AgentToolExecutor> delegates;

    public CompositeAgentToolExecutor(List<? extends AgentToolExecutor> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        List<ModelToolDefinition> definitions = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (AgentToolExecutor delegate : delegates) {
            for (ModelToolDefinition definition : delegate.definitions()) {
                if (!names.add(definition.name())) {
                    throw new IllegalStateException("Duplicate model tool name " + definition.name());
                }
                definitions.add(definition);
            }
        }
        return List.copyOf(definitions);
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        return delegates.stream().flatMap(value -> value.requiredContext().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<String> canonicalToolId(String modelToolName) {
        String resolved = null;
        for (AgentToolExecutor delegate : delegates) {
            Optional<String> candidate = delegate.canonicalToolId(modelToolName);
            if (candidate.isEmpty()) {
                continue;
            }
            if (resolved != null && !resolved.equals(candidate.orElseThrow())) {
                throw new IllegalStateException(
                        "Ambiguous model Tool alias " + modelToolName);
            }
            resolved = candidate.orElseThrow();
        }
        return Optional.ofNullable(resolved);
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        for (AgentToolExecutor delegate : delegates) {
            if (delegate.canonicalToolId(modelToolName).isPresent()) {
                return delegate.execute(modelToolName, arguments, context, cancellation);
            }
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "failure");
        normalized.addProperty("code", "tool_unavailable");
        normalized.addProperty("message", "Tool is unavailable in this request");
        return CompletableFuture.completedFuture(
                new AgentToolResult(UNKNOWN_TOOL_ID, normalized, true));
    }
}
