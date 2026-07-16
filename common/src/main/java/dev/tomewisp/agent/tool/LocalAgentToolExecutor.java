package dev.tomewisp.agent.tool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.replay.ToolArgumentCodec;
import dev.tomewisp.trace.replay.ToolResultNormalizer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class LocalAgentToolExecutor implements AgentToolExecutor {
    private final ToolRegistry tools;
    private final ToolNameCodec names;
    private final ToolArgumentCodec arguments;
    private final ToolResultNormalizer normalizer;
    private final List<ModelToolDefinition> definitions;

    public LocalAgentToolExecutor(ToolRegistry tools, Gson gson) {
        this.tools = Objects.requireNonNull(tools, "tools");
        List<ToolDescriptor<?, ?>> descriptors = tools.descriptors();
        names = new ToolNameCodec(descriptors.stream().map(ToolDescriptor::id).toList());
        arguments = new ToolArgumentCodec(gson);
        normalizer = new ToolResultNormalizer(gson);
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        definitions = descriptors.stream()
                .map(descriptor -> new ModelToolDefinition(
                        names.encode(descriptor.id()),
                        descriptor.description(),
                        schemas.generate(descriptor.inputType())))
                .toList();
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        return definitions;
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        return tools.descriptors().stream()
                .flatMap(descriptor -> descriptor.requiredContext().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject rawArguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        try {
            cancellation.throwIfCancelled();
            String toolId = names.decode(modelToolName);
            Tool<?, ?> tool = tools.find(toolId).orElseThrow();
            ToolResult<?> decoded = arguments.decode(rawArguments, tool.descriptor().inputType());
            ToolResult<?> result;
            if (decoded instanceof ToolResult.Success<?> success) {
                try {
                    result = invoke(tool, context, success.value());
                } catch (RuntimeException exception) {
                    String message = exception.getMessage();
                    result = new ToolResult.Failure<>(
                            "tool_failure",
                            message == null || message.isBlank()
                                    ? exception.getClass().getSimpleName()
                                    : message);
                }
            } else {
                result = decoded;
            }
            JsonObject normalized = normalizer.normalize(result, tool.descriptor().outputType());
            return CompletableFuture.completedFuture(new AgentToolResult(
                    toolId,
                    normalized,
                    result instanceof ToolResult.Failure<?>));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <I, O> ToolResult<O> invoke(
            Tool<?, ?> rawTool, ToolInvocationContext context, Object rawInput) {
        Tool<I, O> tool = (Tool<I, O>) rawTool;
        return tool.invoke(context, (I) rawInput);
    }
}
