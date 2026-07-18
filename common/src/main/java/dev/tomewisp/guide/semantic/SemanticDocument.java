package dev.tomewisp.guide.semantic;

import java.util.List;

/** Immutable versioned player-visible semantic content with exact text fallback. */
public record SemanticDocument(
        int schemaVersion,
        List<SemanticBlock> blocks,
        String fallbackText,
        List<SemanticDiagnostic> diagnostics) {
    public static final int SCHEMA_VERSION = 1;

    public SemanticDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported semantic document schema");
        }
        blocks = List.copyOf(blocks);
        diagnostics = List.copyOf(diagnostics);
        String expected = SemanticPlainText.render(blocks);
        if (!expected.equals(fallbackText)) {
            throw new IllegalArgumentException("semantic fallback does not match document");
        }
    }

    public static SemanticDocument of(
            List<SemanticBlock> blocks, List<SemanticDiagnostic> diagnostics) {
        List<SemanticBlock> copied = List.copyOf(blocks);
        return new SemanticDocument(
                SCHEMA_VERSION, copied, SemanticPlainText.render(copied), diagnostics);
    }

    public static SemanticDocument empty() {
        return of(List.of(), List.of());
    }
}
