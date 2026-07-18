package dev.tomewisp.guide.semantic;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BuiltinRichComponents {
    private BuiltinRichComponents() {}

    static Map<String, RichComponentRegistry.Decoder> decoders() {
        Map<String, RichComponentRegistry.Decoder> values = new LinkedHashMap<>();
        values.put("item_row", BuiltinRichComponents::itemRow);
        values.put("recipe_grid", BuiltinRichComponents::recipeGrid);
        values.put("ingredient_check", BuiltinRichComponents::ingredientCheck);
        values.put("craftability_summary", BuiltinRichComponents::craftability);
        values.put("progress_steps", BuiltinRichComponents::progressSteps);
        values.put("source_summary", BuiltinRichComponents::sourceSummary);
        values.put("status_badge", BuiltinRichComponents::statusBadge);
        values.put("choice_group", BuiltinRichComponents::choiceGroup);
        return Map.copyOf(values);
    }

    private static RichComponent itemRow(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("items"));
        List<RichComponent.Item> items = RichComponentRegistry.objects(
                RichComponentRegistry.array(properties, "items")).stream()
                .map(value -> RichComponentRegistry.item(value, references)).toList();
        return new RichComponent.ItemRow(
                nodeId, items, envelope.fallbackText(), envelope.narration());
    }

    private static RichComponent recipeGrid(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(
                properties, Set.of("sourceId", "generation", "recipeId", "label"));
        RichComponentRegistry.RecipeBinding recipe = RichComponentRegistry.recipe(
                properties, references);
        return new RichComponent.RecipeGrid(
                nodeId,
                recipe.reference(),
                recipe.originInvocationId(),
                RichComponentRegistry.nullableString(properties, "label"),
                envelope.fallbackText(),
                envelope.narration());
    }

    private static RichComponent ingredientCheck(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("ingredients"));
        List<RichComponent.Ingredient> ingredients = RichComponentRegistry.objects(
                        RichComponentRegistry.array(properties, "ingredients")).stream()
                .map(value -> ingredient(value, references)).toList();
        return new RichComponent.IngredientCheck(
                nodeId, ingredients, envelope.fallbackText(), envelope.narration());
    }

    private static RichComponent.Ingredient ingredient(
            JsonObject value, SemanticReferenceIndex references) {
        RichComponentRegistry.exact(
                value, Set.of("itemId", "required", "available", "label"));
        String itemId = RichComponentRegistry.string(value, "itemId");
        return new RichComponent.Ingredient(
                itemId,
                RichComponentRegistry.nonnegativeLong(value, "required"),
                RichComponentRegistry.nonnegativeLong(value, "available"),
                RichComponentRegistry.nullableString(value, "label"),
                RichComponentRegistry.requireOrigin(
                        references, SemanticReferenceKind.ITEM, itemId));
    }

    private static RichComponent craftability(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of(
                "sourceId", "generation", "recipeId", "craftable", "conclusive",
                "requestedCrafts", "maximumCrafts"));
        RichComponentRegistry.RecipeBinding recipe = RichComponentRegistry.recipe(
                properties, references);
        return new RichComponent.CraftabilitySummary(
                nodeId,
                recipe.reference(),
                recipe.originInvocationId(),
                RichComponentRegistry.bool(properties, "craftable"),
                RichComponentRegistry.bool(properties, "conclusive"),
                RichComponentRegistry.positiveLong(properties, "requestedCrafts"),
                RichComponentRegistry.nonnegativeLong(properties, "maximumCrafts"),
                envelope.fallbackText(),
                envelope.narration());
    }

    private static RichComponent progressSteps(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("steps"));
        List<RichComponent.Step> steps = RichComponentRegistry.objects(
                        RichComponentRegistry.array(properties, "steps")).stream()
                .map(BuiltinRichComponents::step).toList();
        return new RichComponent.ProgressSteps(
                nodeId, steps, envelope.fallbackText(), envelope.narration());
    }

    private static RichComponent.Step step(JsonObject value) {
        RichComponentRegistry.exact(value, Set.of("id", "label", "state"));
        return new RichComponent.Step(
                RichComponentRegistry.string(value, "id"),
                RichComponentRegistry.string(value, "label"),
                RichComponent.StepState.valueOf(
                        RichComponentRegistry.string(value, "state")));
    }

    private static RichComponent sourceSummary(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("sources"));
        List<RichComponent.Source> sources = RichComponentRegistry.objects(
                        RichComponentRegistry.array(properties, "sources")).stream()
                .map(value -> source(value, references)).toList();
        return new RichComponent.SourceSummary(
                nodeId, sources, envelope.fallbackText(), envelope.narration());
    }

    private static RichComponent.Source source(
            JsonObject value, SemanticReferenceIndex references) {
        RichComponentRegistry.exact(value, Set.of("sourceId", "label"));
        String sourceId = RichComponentRegistry.string(value, "sourceId");
        return new RichComponent.Source(
                sourceId,
                RichComponentRegistry.nullableString(value, "label"),
                RichComponentRegistry.requireOrigin(
                        references, SemanticReferenceKind.SOURCE, sourceId));
    }

    private static RichComponent statusBadge(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("state", "label"));
        return new RichComponent.StatusBadge(
                nodeId,
                RichComponent.BadgeState.valueOf(
                        RichComponentRegistry.string(properties, "state")),
                RichComponentRegistry.string(properties, "label"),
                envelope.fallbackText(),
                envelope.narration());
    }

    private static RichComponent choiceGroup(
            String nodeId,
            RichComponentEnvelope envelope,
            SemanticReferenceIndex references) {
        JsonObject properties = envelope.properties();
        RichComponentRegistry.exact(properties, Set.of("prompt", "choices"));
        List<RichComponent.Choice> choices = RichComponentRegistry.objects(
                        RichComponentRegistry.array(properties, "choices")).stream()
                .map(BuiltinRichComponents::choice).toList();
        return new RichComponent.ChoiceGroup(
                nodeId,
                RichComponentRegistry.string(properties, "prompt"),
                choices,
                envelope.fallbackText(),
                envelope.narration());
    }

    private static RichComponent.Choice choice(JsonObject value) {
        RichComponentRegistry.exact(value, Set.of("id", "label"));
        return new RichComponent.Choice(
                RichComponentRegistry.string(value, "id"),
                RichComponentRegistry.string(value, "label"));
    }
}
