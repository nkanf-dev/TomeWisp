package dev.tomewisp.context;

import java.util.List;

public record RecipeSnapshot(EvidenceMetadata evidence, List<RecipeEntrySnapshot> recipes) {
    public RecipeSnapshot {
        java.util.Objects.requireNonNull(evidence, "evidence");
        recipes = List.copyOf(recipes);
    }
}
