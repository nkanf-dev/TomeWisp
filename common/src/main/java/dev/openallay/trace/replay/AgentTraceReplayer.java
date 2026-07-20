package dev.openallay.trace.replay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.OpenAllayConstants;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ContextMetrics;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import dev.openallay.trace.model.AgentTrace;
import dev.openallay.trace.model.AssistantMessageStep;
import dev.openallay.trace.model.ToolCallStep;
import dev.openallay.trace.model.TraceStep;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AgentTraceReplayer {
    private final ToolRegistry tools;
    private final Gson gson;
    private final ToolArgumentCodec arguments;
    private final ToolResultNormalizer normalizer;
    private final ExpectationMatcher matcher;
    private final ResourceRequestRegistry resources;

    public AgentTraceReplayer(ToolRegistry tools, Gson gson) {
        this(tools, gson, null);
    }

    public AgentTraceReplayer(
            ToolRegistry tools, Gson gson, ResourceRequestRegistry resources) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.resources = resources;
        arguments = new ToolArgumentCodec(gson);
        normalizer = new ToolResultNormalizer(gson);
        matcher = new ExpectationMatcher();
    }

    public ReplayReport replay(AgentTrace trace, ToolInvocationContext context) {
        long started = System.nanoTime();
        List<ReplayStepReport> reports = new ArrayList<>();
        long resultBytes = 0;

        String missingContext = missingContext(trace, context);
        if (missingContext != null) {
            return report(trace, false, reports, context.metrics(), resultBytes, started, missingContext);
        }

        if (requiresResourceView(trace)) {
            if (resources == null) {
                return report(trace, false, reports, context.metrics(), resultBytes, started,
                        "resource_vfs_unavailable");
            }
            UUID actorId = context.caller().uuid() == null
                    ? UUID.nameUUIDFromBytes(("trace-console:" + context.caller().displayName())
                            .getBytes(StandardCharsets.UTF_8))
                    : context.caller().uuid();
            UUID requestId = UUID.nameUUIDFromBytes(
                    ("trace-request:" + context.correlationId()).getBytes(StandardCharsets.UTF_8));
            Set<String> capabilities = trace.steps().stream()
                    .filter(ToolCallStep.class::isInstance)
                    .map(ToolCallStep.class::cast)
                    .map(ToolCallStep::tool)
                    .collect(Collectors.toUnmodifiableSet());
            ResourceRequestRegistry.RequestHandle handle;
            try {
                handle = resources.open(
                        actorId,
                        "trace:" + trace.id(),
                        requestId,
                        resources.connectionGeneration(actorId),
                        "trace-replay",
                        capabilities,
                        new ContextBudget(100_000, 4_096),
                        context);
            } catch (RuntimeException failure) {
                OpenAllayConstants.LOGGER.error(
                        "Could not bind Resource VFS for trace {}", trace.id(), failure);
                return report(trace, false, reports, context.metrics(), resultBytes, started,
                        "resource_vfs_unavailable");
            }
            try (handle) {
                return replaySteps(trace, context, reports, resultBytes, started);
            }
        }
        return replaySteps(trace, context, reports, resultBytes, started);
    }

    private ReplayReport replaySteps(
            AgentTrace trace,
            ToolInvocationContext context,
            List<ReplayStepReport> reports,
            long resultBytes,
            long started) {

        for (int index = 0; index < trace.steps().size(); index++) {
            TraceStep step = trace.steps().get(index);
            if (step instanceof AssistantMessageStep message) {
                reports.add(new ReplayStepReport(
                        index,
                        "assistant_message",
                        null,
                        true,
                        0,
                        null,
                        null,
                        message.content(),
                        null));
                continue;
            }

            ToolCallStep call = (ToolCallStep) step;
            long stepStarted = System.nanoTime();
            Tool<?, ?> tool = tools.find(call.tool()).orElse(null);
            if (tool == null) {
                String error = "unknown_tool: " + call.tool();
                reports.add(failedStep(index, call, stepStarted, null, error));
                return report(trace, false, reports, context.metrics(), resultBytes, started, error);
            }

            ToolResult<?> decoded = arguments.decode(call.arguments(), tool.descriptor().inputType());
            ToolResult<?> toolResult;
            if (decoded instanceof ToolResult.Failure<?> failure) {
                toolResult = failure;
            } else {
                try {
                    toolResult = invoke(tool, context, ((ToolResult.Success<?>) decoded).value());
                } catch (RuntimeException exception) {
                    OpenAllayConstants.LOGGER.error(
                            "Tool {} failed during trace {}", call.tool(), trace.id(), exception);
                    toolResult = new ToolResult.Failure<>(
                            "tool_failure",
                            exception.getMessage() == null
                                    ? exception.getClass().getName()
                                    : exception.getMessage());
                }
            }

            JsonObject actual = normalizer.normalize(toolResult, tool.descriptor().outputType());
            resultBytes += gson.toJson(actual).getBytes(StandardCharsets.UTF_8).length;
            ExpectationMatcher.MatchResult match = matcher.match(call.expect(), actual);
            reports.add(new ReplayStepReport(
                    index,
                    "tool_call",
                    call.tool(),
                    match.matches(),
                    System.nanoTime() - stepStarted,
                    actual,
                    call.expect().value(),
                    null,
                    match.error()));
            if (!match.matches()) {
                return report(
                        trace,
                        false,
                        reports,
                        context.metrics(),
                        resultBytes,
                        started,
                        match.error());
            }
        }
        return report(trace, true, reports, context.metrics(), resultBytes, started, null);
    }

    private static boolean requiresResourceView(AgentTrace trace) {
        return trace.steps().stream()
                .filter(ToolCallStep.class::isInstance)
                .map(ToolCallStep.class::cast)
                .map(ToolCallStep::tool)
                .anyMatch(tool -> tool.startsWith("openallay:resource_"));
    }

    private static String missingContext(AgentTrace trace, ToolInvocationContext context) {
        for (ContextCapability capability : trace.requiredContext()) {
            boolean present = switch (capability) {
                case REGISTRIES -> context.registries().isPresent();
                case RECIPES -> context.recipes().isPresent();
                case PLAYER -> context.player().isPresent();
                case OBSERVABLE_GAME_STATE -> context.observableGameState().isPresent();
            };
            if (!present) {
                return "missing_context: " + capability.name().toLowerCase();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <I, O> ToolResult<O> invoke(
            Tool<?, ?> rawTool, ToolInvocationContext context, Object rawInput) {
        Tool<I, O> tool = (Tool<I, O>) rawTool;
        return tool.invoke(context, (I) rawInput);
    }

    private static ReplayStepReport failedStep(
            int index, ToolCallStep call, long started, JsonElement actual, String error) {
        return new ReplayStepReport(
                index,
                "tool_call",
                call.tool(),
                false,
                System.nanoTime() - started,
                actual,
                call.expect().value(),
                null,
                error);
    }

    private static ReplayReport report(
            AgentTrace trace,
            boolean passed,
            List<ReplayStepReport> steps,
            ContextMetrics context,
            long resultBytes,
            long started,
            String error) {
        ReplayMetrics metrics = new ReplayMetrics(
                context.registryEntries(),
                context.recipes(),
                context.inventorySlots(),
                context.estimatedSerializedBytes(),
                context.captureNanos(),
                resultBytes,
                System.nanoTime() - started);
        return new ReplayReport(trace.id(), passed, steps, metrics, error);
    }
}
