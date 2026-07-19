package dev.openallay.agent.context;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ReducedToolResult(JsonObject value, boolean error) {
    public ReducedToolResult {
        value = Objects.requireNonNull(value, "value").deepCopy();
    }

    @Override
    public JsonObject value() {
        return value.deepCopy();
    }
}
