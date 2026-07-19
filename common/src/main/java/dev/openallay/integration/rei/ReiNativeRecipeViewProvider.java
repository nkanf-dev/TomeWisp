package dev.openallay.integration.rei;

import dev.openallay.client.gui.nativeview.NativeDomainViewBinding;
import dev.openallay.client.gui.nativeview.NativeDomainViewProvider;

/**
 * REI 26.2 exposes category widgets but no stable exact-display identity contract that can be
 * re-resolved from OpenAllay's durable fingerprint without rebuilding live registry internals.
 * Keep this explicit provider diagnostic so the registry falls through to the neutral canvas.
 */
final class ReiNativeRecipeViewProvider implements NativeDomainViewProvider {
    @Override
    public String providerId() {
        return "viewer:rei";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public boolean supports(NativeDomainViewBinding binding) {
        return binding instanceof NativeDomainViewBinding.Recipe recipe
                && recipe.recipe().references().stream()
                        .anyMatch(reference -> reference.sourceId().equals("viewer:rei"));
    }

    @Override
    public Attempt create(NativeDomainViewBinding binding) {
        return new Attempt.Unsupported("rei_exact_embedding_unsupported");
    }
}
