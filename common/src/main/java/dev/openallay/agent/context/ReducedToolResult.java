package dev.openallay.agent.context;

import com.google.gson.JsonElement;
import java.util.Objects;

public record ReducedToolResult(JsonElement value, boolean error) {
    public ReducedToolResult {
        value = Objects.requireNonNull(value, "value").deepCopy();
    }

    @Override
    public JsonElement value() {
        return value.deepCopy();
    }
}
