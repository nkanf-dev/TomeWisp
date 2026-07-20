package dev.openallay.guide.ui;

import java.util.List;
import java.util.Objects;

/** Closed set of semantic cards the native guide screen is allowed to render. */
public sealed interface GuideDetailCard permits
        GuideDetailCard.Recipe,
        GuideDetailCard.ItemGrid,
        GuideDetailCard.Requirements,
        GuideDetailCard.ResourceSummary,
        GuideDetailCard.Text,
        GuideDetailCard.Error {

    record Recipe(GuideRecipeCard recipe) implements GuideDetailCard {
        public Recipe {
            Objects.requireNonNull(recipe, "recipe");
        }
    }

    record ItemGrid(String titleKey, List<GuideItemView> items) implements GuideDetailCard {
        public ItemGrid {
            titleKey = requireText(titleKey, "titleKey");
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("item grid must not be empty");
            }
        }
    }

    record Requirements(
            boolean craftable,
            boolean conclusive,
            long requestedCrafts,
            long maximumCrafts,
            List<Requirement> requirements) implements GuideDetailCard {
        public Requirements {
            if (requestedCrafts <= 0 || maximumCrafts < 0) {
                throw new IllegalArgumentException("craftability counts are invalid");
            }
            requirements = List.copyOf(requirements);
        }
    }

    record Requirement(
            String key,
            long required,
            long allocated,
            long missing,
            List<GuideItemView> allocatedItems,
            List<GuideItemView> alternatives) {
        public Requirement {
            key = requireText(key, "key");
            if (required <= 0 || allocated < 0 || missing < 0 || allocated + missing != required) {
                throw new IllegalArgumentException("requirement counts are inconsistent");
            }
            allocatedItems = List.copyOf(allocatedItems);
            alternatives = List.copyOf(alternatives);
        }
    }

    record ResourceSummary(
            String operation,
            int succeeded,
            int failed,
            String firstRequestedPath,
            int additionalRequestedPaths,
            List<String> kinds,
            String resultPath,
            boolean continuationAvailable) implements GuideDetailCard {
        public ResourceSummary {
            operation = requireText(operation, "operation");
            if (succeeded < 0 || failed < 0 || additionalRequestedPaths < 0) {
                throw new IllegalArgumentException("resource summary counts must not be negative");
            }
            firstRequestedPath = firstRequestedPath == null ? "" : firstRequestedPath;
            kinds = List.copyOf(kinds);
            resultPath = resultPath == null ? "" : resultPath;
        }
    }

    record Text(String titleKey, List<String> lines) implements GuideDetailCard {
        public Text {
            titleKey = requireText(titleKey, "titleKey");
            lines = List.copyOf(lines);
            if (lines.isEmpty() || lines.stream().anyMatch(line -> line == null || line.isBlank())) {
                throw new IllegalArgumentException("text card lines must not be blank");
            }
        }
    }

    record Error(String message) implements GuideDetailCard {
        public Error {
            message = requireText(message, "message");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
