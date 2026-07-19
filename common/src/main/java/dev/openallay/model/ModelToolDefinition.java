package dev.openallay.model;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ModelToolDefinition(String name, String description, JsonObject inputSchema) {
    public ModelToolDefinition {
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Model tool name and description are required");
        }
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }
}
