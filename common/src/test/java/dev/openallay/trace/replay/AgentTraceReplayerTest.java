package dev.openallay.trace.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import dev.openallay.trace.model.AgentTrace;
import dev.openallay.trace.model.AssistantMessageStep;
import dev.openallay.trace.model.ExpectationMatch;
import dev.openallay.trace.model.ToolCallStep;
import dev.openallay.trace.model.TraceExpectation;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentTraceReplayerTest {
    record Input(String question) {}

    record Output(String answer, List<Integer> evidence) {}

    @Test
    void invokesRealToolAndPreservesAssistantMessageAndMetrics() {
        AtomicInteger invocations = new AtomicInteger();
        AgentTraceReplayer replayer = replayer(invocations);
        AgentTrace trace = trace(
                new ToolCallStep(
                        "test:answer",
                        object("{\"question\":\"how\"}"),
                        new TraceExpectation(
                                "success",
                                ExpectationMatch.CONTAINS,
                                JsonParser.parseString("{\"answer\":\"grounded\"}"),
                                null)),
                new AssistantMessageStep("完整的预录回答。"));

        ReplayReport report = replayer.replay(
                trace, ToolInvocationContext.developmentConsole("replay-test"));

        assertTrue(report.passed());
        assertEquals(1, invocations.get());
        assertEquals(2, report.steps().size());
        assertEquals("完整的预录回答。", report.steps().get(1).assistantMessage());
        assertTrue(report.metrics().toolResultSerializedBytes() > 0);
        assertTrue(report.metrics().totalDurationNanos() > 0);
        assertTrue(report.chatLines().stream().anyMatch(line -> line.contains("完整的预录回答。")));
        assertTrue(report.chatLines().stream().anyMatch(line -> line.startsWith("ACTUAL ")));
    }

    @Test
    void stopsAtFirstMismatchAndRetainsPriorReport() {
        AtomicInteger invocations = new AtomicInteger();
        AgentTrace trace = trace(
                new ToolCallStep(
                        "test:answer",
                        object("{\"question\":\"how\"}"),
                        new TraceExpectation(
                                "success",
                                ExpectationMatch.EXACT,
                                JsonParser.parseString("{\"answer\":\"wrong\"}"),
                                null)),
                new AssistantMessageStep("must not execute"));

        ReplayReport report = replayer(invocations)
                .replay(trace, ToolInvocationContext.developmentConsole("replay-test"));

        assertFalse(report.passed());
        assertEquals(1, invocations.get());
        assertEquals(1, report.steps().size());
        assertFalse(report.steps().getFirst().passed());
    }

    @Test
    void failsUnknownToolsAndMissingRequiredContextWithoutFabrication() {
        AgentTrace unknown = trace(new ToolCallStep(
                "test:missing",
                new JsonObject(),
                new TraceExpectation("success", ExpectationMatch.CONTAINS, new JsonObject(), null)));
        ReplayReport unknownReport = replayer(new AtomicInteger())
                .replay(unknown, ToolInvocationContext.developmentConsole("replay-test"));
        assertFalse(unknownReport.passed());
        assertTrue(unknownReport.error().startsWith("unknown_tool"));

        AgentTrace needsPlayer = new AgentTrace(
                1,
                "needs-player",
                "who am I",
                Set.of(ContextCapability.PLAYER),
                List.of(new AssistantMessageStep("not reached")));
        ReplayReport missingContext = replayer(new AtomicInteger())
                .replay(needsPlayer, ToolInvocationContext.developmentConsole("replay-test"));
        assertFalse(missingContext.passed());
        assertEquals("missing_context: player", missingContext.error());
        assertTrue(missingContext.steps().isEmpty());
    }

    private static AgentTraceReplayer replayer(AtomicInteger invocations) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new Tool<Input, Output>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:answer", "Answer from a real test tool", Input.class, Output.class, ToolAccess.READ_ONLY);

            @Override
            public ToolDescriptor<Input, Output> descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                invocations.incrementAndGet();
                return new ToolResult.Success<>(new Output("grounded", List.of(1, 2, 3)));
            }
        }));
        return new AgentTraceReplayer(registry, new Gson());
    }

    private static AgentTrace trace(dev.openallay.trace.model.TraceStep... steps) {
        return new AgentTrace(1, "test-trace", "test", Set.of(), List.of(steps));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
