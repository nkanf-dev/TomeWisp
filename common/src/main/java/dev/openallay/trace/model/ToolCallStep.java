package dev.openallay.trace.model;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ToolCallStep(String tool, JsonObject arguments, TraceExpectation expect)
        implements TraceStep {
    public ToolCallStep {
        if (tool == null || !tool.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid tool id: " + tool);
        }
        arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
        Objects.requireNonNull(expect, "expect");
    }

    @Override
    public JsonObject arguments() {
        return arguments.deepCopy();
    }
}
