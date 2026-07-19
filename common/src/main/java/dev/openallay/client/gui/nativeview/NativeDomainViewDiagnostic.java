package dev.openallay.client.gui.nativeview;

/** Redacted, presentation-scoped provider diagnostic. */
public record NativeDomainViewDiagnostic(String stableId, String providerId, String code) {
    public NativeDomainViewDiagnostic {
        if (stableId == null || stableId.isBlank()
                || providerId == null || providerId.isBlank()
                || code == null || !code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("native view diagnostic is invalid");
        }
    }
}
