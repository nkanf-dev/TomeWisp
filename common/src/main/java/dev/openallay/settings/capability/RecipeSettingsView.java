package dev.openallay.settings.capability;

import dev.openallay.context.RecipeReference;
import dev.openallay.recipe.config.RecipeClientConfig;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable recipe Tool child-settings projection. */
public record RecipeSettingsView(
        RecipeClientConfig config,
        List<Source> sources,
        Set<String> unknownDisabledSources,
        boolean preferredViewerAvailable) {
    public RecipeSettingsView {
        java.util.Objects.requireNonNull(config, "config");
        sources = List.copyOf(sources).stream()
                .sorted(Comparator.comparing(Source::id))
                .toList();
        unknownDisabledSources = Collections.unmodifiableSet(
                new TreeSet<>(unknownDisabledSources));
    }

    public static RecipeSettingsView defaults() {
        return new RecipeSettingsView(
                RecipeClientConfig.defaults(), List.of(), Set.of(), true);
    }

    public record Source(
            String id,
            boolean available,
            boolean enabled,
            boolean viewer,
            boolean exactNavigation) {
        public Source {
            id = RecipeReference.requireSourceId(id);
            if (exactNavigation && !viewer) {
                throw new IllegalArgumentException("exact navigation requires a viewer source");
            }
        }
    }
}
