package dev.tomewisp.recipe;

import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.RecipeSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public final class RecipeCatalogMerger {
    private static final Comparator<RecipeEntrySnapshot> RECIPE_ORDER = Comparator
            .comparingInt((RecipeEntrySnapshot value) -> authorityRank(value.evidence().authority()))
            .thenComparing(value -> value.reference().sourceId())
            .thenComparing(value -> value.reference().recipeId());

    public RecipeSnapshot merge(
            EvidenceMetadata evidence,
            RecipeVisibilityPolicy visibility,
            List<RecipeProviderSnapshot> providers) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(visibility, "visibility");
        List<RecipeProviderSnapshot> sourceSnapshots = List.copyOf(providers);
        HashSet<String> sourceIds = new HashSet<>();
        sourceSnapshots.forEach(provider -> {
            if (!sourceIds.add(provider.sourceId())) {
                throw new IllegalArgumentException("duplicate recipe provider " + provider.sourceId());
            }
        });

        List<RecipeEntrySnapshot> recipes = sourceSnapshots.stream()
                .filter(provider -> provider.state() == RecipeProviderState.AVAILABLE)
                .flatMap(provider -> provider.recipes().stream())
                .filter(recipe -> visibility == RecipeVisibilityPolicy.ALL_KNOWN
                        || recipe.unlockState() == RecipeUnlockState.UNLOCKED)
                .sorted(RECIPE_ORDER)
                .toList();

        TreeMap<String, List<RecipeEntrySnapshot>> byFingerprint = new TreeMap<>();
        recipes.forEach(recipe -> byFingerprint
                .computeIfAbsent(RecipeCanonicalizer.semanticFingerprint(recipe), ignored -> new ArrayList<>())
                .add(recipe));
        List<RecipeSemanticGroup> groups = byFingerprint.entrySet().stream()
                .map(entry -> semanticGroup(entry.getKey(), entry.getValue()))
                .toList();

        TreeMap<String, List<RecipeEntrySnapshot>> byId = new TreeMap<>();
        recipes.forEach(recipe -> byId.computeIfAbsent(recipe.id(), ignored -> new ArrayList<>())
                .add(recipe));
        List<RecipeCatalogDiagnostic> diagnostics = byId.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .map(RecipeCanonicalizer::semanticFingerprint)
                        .distinct()
                        .count() > 1)
                .map(entry -> new RecipeCatalogDiagnostic(
                        "recipe_id_conflict",
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(RECIPE_ORDER)
                                .map(RecipeEntrySnapshot::reference)
                                .toList(),
                        "Recipe id resolves to different normalized contents"))
                .toList();

        return new RecipeSnapshot(
                withCompleteness(evidence, completeness(sourceSnapshots)),
                recipes,
                sourceSnapshots,
                groups,
                diagnostics);
    }

    private static RecipeSemanticGroup semanticGroup(
            String fingerprint, List<RecipeEntrySnapshot> variants) {
        List<RecipeEntrySnapshot> ordered = variants.stream().sorted(RECIPE_ORDER).toList();
        return new RecipeSemanticGroup(
                fingerprint,
                ordered.getFirst(),
                ordered.stream().map(RecipeEntrySnapshot::reference).toList(),
                ordered.stream().map(RecipeEntrySnapshot::evidence).toList());
    }

    private static DataCompleteness completeness(List<RecipeProviderSnapshot> providers) {
        if (providers.isEmpty()
                || providers.stream().noneMatch(provider ->
                        provider.state() == RecipeProviderState.AVAILABLE)) {
            return DataCompleteness.UNKNOWN;
        }
        boolean complete = providers.stream().allMatch(provider ->
                provider.state() == RecipeProviderState.AVAILABLE
                        && provider.completeness() == DataCompleteness.COMPLETE);
        return complete ? DataCompleteness.COMPLETE : DataCompleteness.PARTIAL;
    }

    private static EvidenceMetadata withCompleteness(
            EvidenceMetadata evidence, DataCompleteness completeness) {
        return new EvidenceMetadata(
                evidence.authority(),
                completeness,
                evidence.capturedAt(),
                evidence.sourceId(),
                evidence.provenance(),
                evidence.gameVersion(),
                evidence.loader(),
                evidence.details());
    }

    private static int authorityRank(DataAuthority authority) {
        return switch (authority) {
            case SERVER_AUTHORITATIVE -> 0;
            case CLIENT_VISIBLE -> 1;
            case INTEGRATION_API -> 2;
            case RESOURCE_ASSET -> 3;
            case DETERMINISTIC_TEST -> 4;
        };
    }
}
