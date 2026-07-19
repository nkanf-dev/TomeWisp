package dev.tomewisp.trace.replay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tomewisp.TomeWispConstants;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.model.AgentTrace;
import dev.tomewisp.trace.model.AssistantMessageStep;
import dev.tomewisp.trace.model.ToolCallStep;
import dev.tomewisp.trace.model.TraceStep;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentTraceReplayer {
    private final ToolRegistry tools;
    private final Gson gson;
    private final ToolArgumentCodec arguments;
    private final ToolResultNormalizer normalizer;
    private final ExpectationMatcher matcher;

    public AgentTraceReplayer(ToolRegistry tools, Gson gson) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.gson = Objects.requireNonNull(gson, "gson");
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
                    TomeWispConstants.LOGGER.error(
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
