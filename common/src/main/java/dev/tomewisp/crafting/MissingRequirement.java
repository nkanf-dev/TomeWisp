package dev.tomewisp.crafting;

import java.util.List;

public record MissingRequirement(
        String requirementKey,
        long required,
        long allocated,
        long missing,
        List<String> alternatives) {
    public MissingRequirement {
        if (requirementKey == null || requirementKey.isBlank()
                || required <= 0
                || allocated < 0
                || missing <= 0
                || allocated + missing != required) {
            throw new IllegalArgumentException("missing requirement counts are inconsistent");
        }
        alternatives = List.copyOf(alternatives);
    }
}
