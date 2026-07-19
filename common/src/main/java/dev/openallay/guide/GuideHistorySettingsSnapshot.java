package dev.openallay.guide;

import dev.openallay.guide.history.GuideHistoryActivity;
import dev.openallay.guide.history.GuideHistoryScope;
import java.util.Objects;
import java.util.Optional;

/** Atomic manager view for settings adapters; it carries no database path or raw scope ID. */
public record GuideHistorySettingsSnapshot(
        boolean configured,
        Optional<GuideSnapshot> guide,
        GuideHistoryActivity activity,
        Optional<GuideHistoryScope.Kind> scopeKind) {
    public GuideHistorySettingsSnapshot {
        guide = Objects.requireNonNull(guide, "guide");
        Objects.requireNonNull(activity, "activity");
        scopeKind = Objects.requireNonNull(scopeKind, "scopeKind");
        if (guide.isPresent() != scopeKind.isPresent()) {
            throw new IllegalArgumentException(
                    "connected history settings require one friendly scope kind");
        }
        if (!configured && (guide.isPresent() || !activity.idleForDeletion())) {
            throw new IllegalArgumentException(
                    "unconfigured history cannot publish connection activity");
        }
    }

    public static GuideHistorySettingsSnapshot unavailable(boolean configured) {
        return new GuideHistorySettingsSnapshot(
                configured,
                Optional.empty(),
                GuideHistoryActivity.idle(),
                Optional.empty());
    }
}
