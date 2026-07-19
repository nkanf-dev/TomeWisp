package dev.tomewisp.client.gui.nativeview;

/** Optional provider for one closed native domain-view family. */
public interface NativeDomainViewProvider {
    String providerId();

    int priority();

    boolean supports(NativeDomainViewBinding binding);

    Attempt create(NativeDomainViewBinding binding);

    sealed interface Attempt permits Attempt.Ready, Attempt.Unsupported {
        record Ready(NativeDomainView view) implements Attempt {
            public Ready {
                java.util.Objects.requireNonNull(view, "view");
            }
        }

        record Unsupported(String code) implements Attempt {
            public Unsupported {
                if (code == null || !code.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("native view diagnostic code is invalid");
                }
            }
        }
    }
}
