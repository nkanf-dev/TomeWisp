package dev.openallay.agent.tool;

import java.util.List;

/** Small, closed Tool-card projection computed off the render thread. */
public record ToolUiSummary(
        String operation,
        int succeeded,
        int failed,
        List<String> resourceKinds) {
    public ToolUiSummary {
        operation = operation == null ? "" : operation;
        if (succeeded < 0 || failed < 0) {
            throw new IllegalArgumentException("Tool UI summary counts must not be negative");
        }
        resourceKinds = List.copyOf(resourceKinds);
    }

    public static ToolUiSummary none() {
        return new ToolUiSummary("", 0, 0, List.of());
    }
}
