package dev.tomewisp.recipe;

import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.RecipeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecipeKnowledgeService {
    private final RecipeCatalogMerger merger;

    public RecipeKnowledgeService() {
        this(new RecipeCatalogMerger());
    }

    RecipeKnowledgeService(RecipeCatalogMerger merger) {
        this.merger = Objects.requireNonNull(merger, "merger");
    }

    public RecipeSnapshot capture(
            EvidenceMetadata evidence,
            RecipeVisibilityPolicy visibility,
            List<RecipeKnowledgeProvider> providers) {
        List<RecipeProviderSnapshot> snapshots = new ArrayList<>();
        for (RecipeKnowledgeProvider provider : List.copyOf(providers)) {
            String sourceId = RecipeReference.requireSourceId(provider.sourceId());
            try {
                RecipeProviderSnapshot snapshot = Objects.requireNonNull(
                        provider.capture(), "recipe provider snapshot");
                if (!snapshot.sourceId().equals(sourceId)) {
                    throw new IllegalArgumentException("recipe provider returned another source id");
                }
                snapshots.add(snapshot);
            } catch (RuntimeException failure) {
                snapshots.add(RecipeProviderSnapshot.failed(
                        sourceId,
                        "capture_failed",
                        "Recipe provider capture failed"));
            }
        }
        return merger.merge(evidence, visibility, snapshots);
    }
}
