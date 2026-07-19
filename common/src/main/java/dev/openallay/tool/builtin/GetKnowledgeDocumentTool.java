package dev.openallay.tool.builtin;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeDocument;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;

public final class GetKnowledgeDocumentTool
        implements Tool<GetKnowledgeDocumentTool.Input, GetKnowledgeDocumentTool.Output> {
    public record Input(String sourceId, String documentId) {}
    public record Output(KnowledgeDocument document, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:get_knowledge_document",
            "Read one complete indexed guide or visible quest document by source and document ID",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);
    private final KnowledgeRegistry registry;

    public GetKnowledgeDocumentTool(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.sourceId() == null || input.documentId() == null) {
            return new ToolResult.Failure<>("invalid_arguments", "sourceId and documentId are required");
        }
        return registry.snapshot().documents().stream()
                .filter(document -> document.sourceId().equals(input.sourceId())
                        && document.documentId().equals(input.documentId()))
                .findFirst()
                .<ToolResult<Output>>map(document -> new ToolResult.Success<>(new Output(
                        document, List.of(document.evidence()))))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "knowledge_not_found", "No visible indexed document matches that identity"));
    }
}
