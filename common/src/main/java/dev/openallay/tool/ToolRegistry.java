package dev.openallay.tool;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class ToolRegistry {
    private final Map<String, RegisteredTool> tools = new TreeMap<>();

    public synchronized void register(
            String providerId, Collection<? extends Tool<?, ?>> newTools) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider id must not be blank");
        }
        List<? extends Tool<?, ?>> snapshot = List.copyOf(newTools);
        for (Tool<?, ?> tool : snapshot) {
            RegisteredTool existing = tools.get(tool.descriptor().id());
            if (existing != null) {
                throw new IllegalStateException("Duplicate tool id "
                        + tool.descriptor().id()
                        + " from "
                        + providerId
                        + "; already registered by "
                        + existing.providerId());
            }
        }
        for (Tool<?, ?> tool : snapshot) {
            tools.put(tool.descriptor().id(), new RegisteredTool(providerId, tool));
        }
    }

    public synchronized Optional<Tool<?, ?>> find(String id) {
        RegisteredTool value = tools.get(id);
        return value == null ? Optional.empty() : Optional.of(value.tool());
    }

    public synchronized List<ToolDescriptor<?, ?>> descriptors() {
        return tools.values().stream()
                .<ToolDescriptor<?, ?>>map(value -> value.tool().descriptor())
                .toList();
    }

    /** Returns a detached, stable-ID-ordered view of the authoritative registrations. */
    public synchronized List<RegisteredTool> registrations() {
        return tools.values().stream().toList();
    }
}
