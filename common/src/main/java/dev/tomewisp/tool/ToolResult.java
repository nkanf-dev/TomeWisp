package dev.tomewisp.tool;

import java.util.Objects;

public sealed interface ToolResult<O> permits ToolResult.Success, ToolResult.Failure {
    record Success<O>(O value) implements ToolResult<O> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<O>(String code, String message) implements ToolResult<O> {
        public Failure {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("Failure code and message are required");
            }
        }
    }
}
