package dev.openallay.trace.replay;

import java.util.List;
import java.util.ArrayList;

public record ReplayReport(
        String traceId,
        boolean passed,
        List<ReplayStepReport> steps,
        ReplayMetrics metrics,
        String error) {
    public ReplayReport {
        steps = List.copyOf(steps);
    }

    public List<String> chatLines() {
        List<String> lines = new ArrayList<>();
        lines.add("TRACE " + traceId + " " + (passed ? "PASS" : "FAIL"));
        for (ReplayStepReport step : steps) {
            String tool = step.tool() == null ? "" : " tool=" + step.tool();
            lines.add("STEP " + step.index() + " " + step.type() + tool + " "
                    + (step.passed() ? "PASS" : "FAIL") + " nanos=" + step.elapsedNanos());
            if (step.actual() != null) {
                lines.add("ACTUAL " + step.actual());
            }
            if (step.expected() != null) {
                lines.add("EXPECTED " + step.expected());
            }
            if (step.assistantMessage() != null) {
                lines.add("ASSISTANT " + step.assistantMessage());
            }
            if (step.error() != null) {
                lines.add("STEP_ERROR " + step.error());
            }
        }
        lines.add("METRICS registryEntries=" + metrics.registryEntries()
                + " recipes=" + metrics.recipes()
                + " inventorySlots=" + metrics.inventorySlots()
                + " contextBytes=" + metrics.contextEstimatedSerializedBytes()
                + " contextCaptureNanos=" + metrics.contextCaptureNanos()
                + " toolResultBytes=" + metrics.toolResultSerializedBytes()
                + " totalNanos=" + metrics.totalDurationNanos());
        if (error != null) {
            lines.add("ERROR " + error);
        }
        return List.copyOf(lines);
    }
}
