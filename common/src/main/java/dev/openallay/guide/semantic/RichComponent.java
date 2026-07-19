package dev.openallay.guide.semantic;

import dev.openallay.context.RecipeReference;
import java.util.List;

/** Closed set of model-selectable components; behavior is inferred by type, never payload code. */
public sealed interface RichComponent
        permits RichComponent.ItemRow, RichComponent.RecipeGrid,
                RichComponent.IngredientCheck, RichComponent.CraftabilitySummary,
                RichComponent.ProgressSteps, RichComponent.SourceSummary,
                RichComponent.StatusBadge, RichComponent.ChoiceGroup {
    String nodeId();

    String fallbackText();

    String narration();

    record Item(String itemId, long count, String label, String originInvocationId) {
        public Item {
            if (!SemanticReferenceValidator.isResourceId(itemId) || count < 0) {
                throw new IllegalArgumentException("component item is invalid");
            }
            label = safeLabel(label);
            originInvocationId = requireOrigin(originInvocationId);
        }
    }

    record ItemRow(
            String nodeId,
            List<Item> items,
            String fallbackText,
            String narration) implements RichComponent {
        public ItemRow {
            common(nodeId, fallbackText, narration);
            items = List.copyOf(items);
            if (items.isEmpty()) throw new IllegalArgumentException("item row must not be empty");
        }
    }

    record RecipeGrid(
            String nodeId,
            RecipeReference recipe,
            String originInvocationId,
            String label,
            String fallbackText,
            String narration) implements RichComponent {
        public RecipeGrid {
            common(nodeId, fallbackText, narration);
            java.util.Objects.requireNonNull(recipe, "recipe");
            originInvocationId = requireOrigin(originInvocationId);
            label = safeLabel(label);
        }
    }

    record Ingredient(
            String itemId,
            long required,
            long available,
            String label,
            String originInvocationId) {
        public Ingredient {
            if (!SemanticReferenceValidator.isResourceId(itemId)
                    || required < 0 || available < 0) {
                throw new IllegalArgumentException("ingredient check entry is invalid");
            }
            label = safeLabel(label);
            originInvocationId = requireOrigin(originInvocationId);
        }
    }

    record IngredientCheck(
            String nodeId,
            List<Ingredient> ingredients,
            String fallbackText,
            String narration) implements RichComponent {
        public IngredientCheck {
            common(nodeId, fallbackText, narration);
            ingredients = List.copyOf(ingredients);
            if (ingredients.isEmpty()) {
                throw new IllegalArgumentException("ingredient check must not be empty");
            }
        }
    }

    record CraftabilitySummary(
            String nodeId,
            RecipeReference recipe,
            String originInvocationId,
            boolean craftable,
            boolean conclusive,
            long requestedCrafts,
            long maximumCrafts,
            String fallbackText,
            String narration) implements RichComponent {
        public CraftabilitySummary {
            common(nodeId, fallbackText, narration);
            java.util.Objects.requireNonNull(recipe, "recipe");
            originInvocationId = requireOrigin(originInvocationId);
            if (requestedCrafts <= 0 || maximumCrafts < 0) {
                throw new IllegalArgumentException("craftability counts are invalid");
            }
        }
    }

    record Step(String id, String label, StepState state) {
        public Step {
            id = requireLocalId(id);
            label = requireText(label, "step label");
            rejectActionText(label);
            java.util.Objects.requireNonNull(state, "state");
        }
    }

    enum StepState { PENDING, ACTIVE, COMPLETE, FAILED }

    record ProgressSteps(
            String nodeId,
            List<Step> steps,
            String fallbackText,
            String narration) implements RichComponent {
        public ProgressSteps {
            common(nodeId, fallbackText, narration);
            steps = List.copyOf(steps);
            if (steps.isEmpty() || steps.stream().map(Step::id).distinct().count() != steps.size()) {
                throw new IllegalArgumentException("progress steps are empty or duplicated");
            }
        }
    }

    record Source(String sourceId, String label, String originInvocationId) {
        public Source {
            if (!SemanticReferenceValidator.isResourceId(sourceId)) {
                throw new IllegalArgumentException("source ID is invalid");
            }
            label = safeLabel(label);
            originInvocationId = requireOrigin(originInvocationId);
        }
    }

    record SourceSummary(
            String nodeId,
            List<Source> sources,
            String fallbackText,
            String narration) implements RichComponent {
        public SourceSummary {
            common(nodeId, fallbackText, narration);
            sources = List.copyOf(sources);
            if (sources.isEmpty()) throw new IllegalArgumentException("source summary is empty");
        }
    }

    enum BadgeState { INFO, SUCCESS, WARNING, ERROR }

    record StatusBadge(
            String nodeId,
            BadgeState state,
            String label,
            String fallbackText,
            String narration) implements RichComponent {
        public StatusBadge {
            common(nodeId, fallbackText, narration);
            java.util.Objects.requireNonNull(state, "state");
            label = requireText(label, "badge label");
            rejectActionText(label);
        }
    }

    record Choice(String id, String label) {
        public Choice {
            id = requireLocalId(id);
            label = requireText(label, "choice label");
            rejectActionText(label);
        }
    }

    record ChoiceGroup(
            String nodeId,
            String prompt,
            List<Choice> choices,
            String fallbackText,
            String narration) implements RichComponent {
        public ChoiceGroup {
            common(nodeId, fallbackText, narration);
            prompt = requireText(prompt, "choice prompt");
            rejectActionText(prompt);
            choices = List.copyOf(choices);
            if (choices.isEmpty()
                    || choices.stream().map(Choice::id).distinct().count() != choices.size()) {
                throw new IllegalArgumentException("choices are empty or duplicated");
            }
        }
    }

    private static void common(String nodeId, String fallback, String narration) {
        SemanticIds.require(nodeId);
        requireText(fallback, "component fallback");
        requireText(narration, "component narration");
    }

    private static String safeLabel(String value) {
        if (value == null) return "";
        String label = value.strip();
        rejectActionText(label);
        return label;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String requireOrigin(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("component reference origin is required");
        }
        return value;
    }

    private static String requireLocalId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("component-local ID is invalid");
        }
        return value;
    }

    private static void rejectActionText(String value) {
        String lowered = value.toLowerCase(java.util.Locale.ROOT);
        if (lowered.contains("://") || lowered.startsWith("javascript:")
                || lowered.startsWith("file:")) {
            throw new IllegalArgumentException("component display text cannot name a URL");
        }
    }
}
