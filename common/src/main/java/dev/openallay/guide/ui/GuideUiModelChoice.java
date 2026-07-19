package dev.openallay.guide.ui;

import dev.openallay.guide.GuideModelSelection;

/** Friendly, credential-free model choice for one guide session. */
public record GuideUiModelChoice(
        GuideModelSelection selection,
        String displayName,
        boolean available,
        boolean selected,
        boolean running) {
    public GuideUiModelChoice {
        java.util.Objects.requireNonNull(selection, "selection");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("model choice display name must not be blank");
        }
    }
}
