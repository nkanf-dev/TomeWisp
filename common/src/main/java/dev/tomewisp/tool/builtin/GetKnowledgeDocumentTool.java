package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;

public final class GetKnowledgeDocumentTool
        implements Tool<GetKnowledgeDocumentTool.Input, GetKnowledgeDocumentTool.Output> {
    public record Input(String sourceId, String documentId) {}
    public record Output(KnowledgeDocument document) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:get_knowledge_document",
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
                .<ToolResult<Output>>map(document -> new ToolResult.Success<>(new Output(document)))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "knowledge_not_found", "No visible indexed document matches that identity"));
    }
}
