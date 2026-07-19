package dev.tomewisp.recipe;

import dev.tomewisp.context.RecipeReference;
import java.util.List;
import java.util.Objects;

/**
 * Samples each required provider until its current registry has produced a non-empty immutable
 * snapshot. Viewer registries can be cleared and repopulated during reload, so readiness is never
 * cached across ticks.
 */
public final class RecipeProviderReadinessGate {
    public RecipeProviderReadiness evaluate(
            List<String> requiredSourceIds, List<RecipeKnowledgeProvider> providers) {
        List<String> required = List.copyOf(requiredSourceIds).stream()
                .map(RecipeReference::requireSourceId)
                .distinct()
                .sorted()
                .toList();
        List<RecipeKnowledgeProvider> available = List.copyOf(providers);

        for (String sourceId : required) {
            RecipeKnowledgeProvider provider = available.stream()
                    .filter(candidate -> sourceId.equals(candidate.sourceId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null) {
                return RecipeProviderReadiness.waiting(
                        "recipe_provider_loading",
                        "Installed recipe viewer has not registered provider " + sourceId);
            }

            RecipeProviderSnapshot snapshot;
            try {
                snapshot = Objects.requireNonNull(provider.capture(), "recipe provider snapshot");
                if (!sourceId.equals(snapshot.sourceId())) {
                    return RecipeProviderReadiness.failed(
                            "recipe_provider_mismatch",
                            "Recipe provider returned another source for " + sourceId);
                }
            } catch (RuntimeException failure) {
                return RecipeProviderReadiness.failed(
                        "recipe_provider_failed",
                        "Recipe provider capture failed for " + sourceId);
            }

            if (snapshot.state() == RecipeProviderState.FAILED) {
                return RecipeProviderReadiness.failed(
                        "recipe_provider_failed",
                        diagnostic(snapshot, "Recipe provider failed for " + sourceId));
            }
            if (snapshot.state() != RecipeProviderState.AVAILABLE || snapshot.recipes().isEmpty()) {
                return RecipeProviderReadiness.waiting(
                        "recipe_provider_loading",
                        diagnostic(snapshot, "Recipe provider has not published recipes for " + sourceId));
            }
        }
        return RecipeProviderReadiness.ready();
    }

    private static String diagnostic(RecipeProviderSnapshot snapshot, String fallback) {
        return snapshot.diagnostics().stream()
                .map(RecipeProviderDiagnostic::message)
                .findFirst()
                .orElse(fallback);
    }
}
