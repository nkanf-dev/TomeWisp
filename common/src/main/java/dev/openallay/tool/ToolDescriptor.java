package dev.openallay.tool;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import dev.openallay.context.ContextCapability;

public record ToolDescriptor<I, O>(
        String id,
        String description,
        Class<I> inputType,
        Class<O> outputType,
        ToolAccess access,
        Set<ContextCapability> requiredContext) {
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
        requiredContext = Set.copyOf(requiredContext);
    }

    public ToolDescriptor(
            String id,
            String description,
            Class<I> inputType,
            Class<O> outputType,
            ToolAccess access) {
        this(id, description, inputType, outputType, access, Set.of());
    }
}
