package dev.tomewisp.guide.history;

import java.util.List;

/** One ordered atomic mutation batch for exactly one durable partition. */
public record GuideHistoryCommit(
        GuideHistoryScope scope,
        List<GuideHistoryMutation> mutations) {
    public GuideHistoryCommit {
        java.util.Objects.requireNonNull(scope, "scope");
        mutations = List.copyOf(mutations);
        if (mutations.isEmpty()) {
            throw new IllegalArgumentException("history commit must not be empty");
        }
    }
}
