package dev.openallay.bridge.server;

import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolRegistry;
import java.util.Optional;
import java.util.Set;

public final class ExportedToolPolicy {
    private final ToolRegistry tools;
    private final Set<String> exported;

    public ExportedToolPolicy(ToolRegistry tools, Set<String> exported) {
        this.tools = tools;
        this.exported = Set.copyOf(exported);
        for (String id : this.exported) {
            Tool<?, ?> tool = tools.find(id).orElseThrow(() ->
                    new IllegalArgumentException("Cannot export unknown tool " + id));
            if (tool.descriptor().access() != ToolAccess.READ_ONLY) {
                throw new IllegalArgumentException("Cannot remotely export non-read-only tool " + id);
            }
        }
    }

    public Optional<Tool<?, ?>> find(String id) {
        return exported.contains(id) ? tools.find(id) : Optional.empty();
    }

    public Set<String> ids() {
        return exported;
    }
}
