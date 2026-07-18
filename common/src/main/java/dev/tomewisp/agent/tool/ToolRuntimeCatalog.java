package dev.tomewisp.agent.tool;

import dev.tomewisp.tool.RegisteredTool;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolDescriptor;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable filtered Tool catalog captured by one Agent runtime. */
public final class ToolRuntimeCatalog {
    private final List<RegisteredTool> registrations;
    private final Map<String, Tool<?, ?>> byId;
    private final Map<String, String> knownIdByModelName;

    private ToolRuntimeCatalog(
            List<RegisteredTool> registrations,
            Map<String, Tool<?, ?>> byId,
            Map<String, String> knownIdByModelName) {
        this.registrations = List.copyOf(registrations);
        this.byId = Map.copyOf(byId);
        this.knownIdByModelName = Map.copyOf(knownIdByModelName);
    }

    public static ToolRuntimeCatalog from(
            Collection<RegisteredTool> registrations, Set<String> disabledToolIds) {
        Objects.requireNonNull(registrations, "registrations");
        Set<String> disabled = Set.copyOf(disabledToolIds);
        List<RegisteredTool> ordered = List.copyOf(registrations).stream()
                .sorted(java.util.Comparator.comparing(value -> value.tool().descriptor().id()))
                .toList();
        ToolNameCodec allNames = new ToolNameCodec(ordered.stream()
                .map(value -> value.tool().descriptor().id())
                .toList());
        Map<String, String> knownNames = new LinkedHashMap<>();
        Map<String, Tool<?, ?>> active = new LinkedHashMap<>();
        java.util.ArrayList<RegisteredTool> filtered = new java.util.ArrayList<>();
        for (RegisteredTool registration : ordered) {
            String toolId = registration.tool().descriptor().id();
            if (knownNames.put(allNames.encode(toolId), toolId) != null
                    || active.containsKey(toolId)) {
                throw new IllegalArgumentException("Duplicate Tool identity: " + toolId);
            }
            if (!disabled.contains(toolId)) {
                active.put(toolId, registration.tool());
                filtered.add(registration);
            }
        }
        return new ToolRuntimeCatalog(filtered, active, knownNames);
    }

    public List<RegisteredTool> registrations() {
        return registrations;
    }

    public List<ToolDescriptor<?, ?>> descriptors() {
        return registrations.stream()
                .<ToolDescriptor<?, ?>>map(value -> value.tool().descriptor())
                .toList();
    }

    public Optional<Tool<?, ?>> find(String toolId) {
        return Optional.ofNullable(byId.get(toolId));
    }

    Optional<String> knownToolId(String modelToolName) {
        return Optional.ofNullable(knownIdByModelName.get(modelToolName));
    }
}
