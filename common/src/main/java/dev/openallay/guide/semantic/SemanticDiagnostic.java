package dev.openallay.guide.semantic;

/** Redacted parser diagnostic; it deliberately carries no source/provider payload. */
public record SemanticDiagnostic(String code, String nodeId) {
    public SemanticDiagnostic {
        if (code == null || !code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("semantic diagnostic code is invalid");
        }
        SemanticIds.require(nodeId);
    }
}
