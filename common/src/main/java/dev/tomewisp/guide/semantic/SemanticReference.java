package dev.tomewisp.guide.semantic;

/** Validated semantic reference; grounded authority is explicit and immutable. */
public record SemanticReference(
        SemanticReferenceKind kind,
        String target,
        String label,
        boolean grounded,
        String originInvocationId) {
    public SemanticReference {
        java.util.Objects.requireNonNull(kind, "kind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("semantic reference target is required");
        }
        label = label == null ? "" : label.strip();
        if (!grounded && originInvocationId != null) {
            throw new IllegalArgumentException("ungrounded reference cannot name an invocation");
        }
        if (grounded && (originInvocationId == null || originInvocationId.isBlank())) {
            throw new IllegalArgumentException("grounded reference requires its invocation origin");
        }
    }

    public String displayText() {
        return label.isBlank() ? target : label;
    }
}
