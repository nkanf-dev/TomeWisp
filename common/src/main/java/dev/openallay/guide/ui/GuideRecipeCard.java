package dev.openallay.guide.ui;

import dev.openallay.context.RecipeReference;
import java.util.List;

/** Safe first-class projection for recipe results rendered by the native screen. */
public record GuideRecipeCard(
        RecipeReference reference,
        List<RecipeReference> references,
        String id,
        String type,
        String workstation,
        List<Output> outputs,
        List<Ingredient> ingredients,
        List<Ingredient> catalysts,
        List<Output> byproducts,
        Processing processing) {
    public GuideRecipeCard {
        java.util.Objects.requireNonNull(reference, "reference");
        references = List.copyOf(references);
        if (references.isEmpty() || !references.contains(reference)) {
            throw new IllegalArgumentException("recipe card references are incomplete");
        }
        if (id == null || id.isBlank() || type == null || type.isBlank()) {
            throw new IllegalArgumentException("recipe card identity is invalid");
        }
        workstation = workstation == null ? "" : workstation;
        outputs = List.copyOf(outputs);
        ingredients = List.copyOf(ingredients);
        catalysts = List.copyOf(catalysts);
        byproducts = List.copyOf(byproducts);
        processing = java.util.Objects.requireNonNull(processing, "processing");
    }

    public GuideRecipeCard(
            RecipeReference reference,
            List<RecipeReference> references,
            String id,
            String type,
            String workstation,
            List<Output> outputs) {
        this(reference, references, id, type, workstation, outputs,
                List.of(), List.of(), List.of(), Processing.unknown());
    }

    public record Output(String itemId, int count, String displayName) {
        public Output {
            if (itemId == null || itemId.isBlank() || count <= 0) {
                throw new IllegalArgumentException("recipe card output is invalid");
            }
            displayName = displayName == null || displayName.isBlank() ? itemId : displayName;
        }
    }

    public record Ingredient(
            String key,
            long count,
            boolean consumed,
            List<Alternative> alternatives) {
        public Ingredient {
            if (key == null || key.isBlank() || count <= 0) {
                throw new IllegalArgumentException("recipe ingredient is invalid");
            }
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("recipe ingredient has no alternatives");
            }
        }
    }

    public record Alternative(String kind, String id, List<String> resolvedItems) {
        public Alternative {
            if (kind == null || kind.isBlank() || id == null || id.isBlank()) {
                throw new IllegalArgumentException("recipe alternative is invalid");
            }
            resolvedItems = List.copyOf(resolvedItems);
        }
    }

    public record Processing(Long durationTicks, Long energy, Double temperature) {
        public Processing {
            if ((durationTicks != null && durationTicks < 0)
                    || (energy != null && energy < 0)
                    || (temperature != null && !Double.isFinite(temperature))) {
                throw new IllegalArgumentException("recipe processing metadata is invalid");
            }
        }

        public static Processing unknown() {
            return new Processing(null, null, null);
        }
    }
}
