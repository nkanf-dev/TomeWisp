package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;

public final class ListKnowledgeSourcesTool
        implements Tool<ListKnowledgeSourcesTool.Input, ListKnowledgeSourcesTool.Output> {
    public record Input() {}
    public record Source(String id, int documents) {}
    public record Output(List<Source> sources, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            sources = List.copyOf(sources);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:list_knowledge_sources",
            "List indexed in-game guide and quest knowledge sources",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);
    private final KnowledgeRegistry registry;

    public ListKnowledgeSourcesTool(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        var snapshot = registry.snapshot();
        List<Source> sources = snapshot.documents().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        dev.tomewisp.knowledge.KnowledgeDocument::sourceId,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new Source(entry.getKey(), Math.toIntExact(entry.getValue())))
                .toList();
        return new ToolResult.Success<>(new Output(sources, snapshot.evidence()));
    }
}
