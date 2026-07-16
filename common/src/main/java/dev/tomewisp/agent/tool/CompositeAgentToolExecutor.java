package dev.tomewisp.agent.tool;

import com.google.gson.JsonObject;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.ArrayList;
import java.util.List;
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
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        for (AgentToolExecutor delegate : delegates) {
            if (delegate.definitions().stream().anyMatch(value -> value.name().equals(modelToolName))) {
                return delegate.execute(modelToolName, arguments, context, cancellation);
            }
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown model tool " + modelToolName));
    }
}
