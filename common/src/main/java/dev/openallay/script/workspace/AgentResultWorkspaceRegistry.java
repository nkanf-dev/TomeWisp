package dev.openallay.script.workspace;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentResultWorkspaceRegistry {
    private final ConcurrentHashMap<String, AgentResultWorkspace> workspaces =
            new ConcurrentHashMap<>();

    public AgentResultWorkspace open(String correlationId) {
        return workspaces.computeIfAbsent(requireId(correlationId), ignored -> new AgentResultWorkspace());
    }

    public void close(String correlationId) {
        AgentResultWorkspace workspace = workspaces.remove(requireId(correlationId));
        if (workspace != null) {
            workspace.close();
        }
    }

    public int activeCount() {
        return workspaces.size();
    }

    public void closeAll() {
        workspaces.values().forEach(AgentResultWorkspace::close);
        workspaces.clear();
    }

    private static String requireId(String value) {
        if (Objects.requireNonNull(value, "correlationId").isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        return value;
    }
}

