package dev.tomewisp.integration.patchouli;

import dev.tomewisp.knowledge.KnowledgeDiagnostic;
import dev.tomewisp.knowledge.KnowledgeDocument;
import java.util.List;
import java.util.Map;

public record PatchouliParseResult(
        List<KnowledgeDocument> documents,
        Map<String, PatchouliMultiblock> multiblocks,
        List<KnowledgeDiagnostic> diagnostics) {
    public PatchouliParseResult {
        documents = List.copyOf(documents);
        multiblocks = Map.copyOf(multiblocks);
        diagnostics = List.copyOf(diagnostics);
    }
}
