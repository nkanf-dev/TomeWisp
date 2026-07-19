package dev.openallay.recipe;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeReference;
import java.util.List;
import java.util.Objects;

public record RecipeSemanticGroup(
        String fingerprint,
        RecipeEntrySnapshot representative,
        List<RecipeReference> references,
        List<EvidenceMetadata> evidence) {
    public RecipeSemanticGroup {
        fingerprint = RecipeReference.requireGeneration(fingerprint);
        Objects.requireNonNull(representative, "representative");
        references = List.copyOf(references);
        evidence = List.copyOf(evidence);
        if (references.isEmpty() || evidence.isEmpty() || references.size() != evidence.size()) {
            throw new IllegalArgumentException("semantic group must retain every reference and evidence record");
        }
        if (!references.contains(representative.reference())) {
            throw new IllegalArgumentException("semantic group representative is not retained");
        }
    }
}
