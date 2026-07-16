package dev.tomewisp.tool;

import java.util.Objects;
import java.util.regex.Pattern;

public record ToolDescriptor<I, O>(
        String id,
        String description,
        Class<I> inputType,
        Class<O> outputType,
        ToolAccess access) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ToolDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid tool id: " + id);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(access, "access");
    }
}
