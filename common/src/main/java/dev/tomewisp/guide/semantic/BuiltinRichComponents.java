package dev.tomewisp.guide.semantic;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BuiltinRichComponents {
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(
                    "item_row",
                    BuiltinRichComponents::itemRow,
                    "Use for a compact row of evidenced items. Exact properties: "
                            + "{\"items\":[{\"itemId\":string,\"count\":nonnegative integer,"
                            + "\"label\":string|null}]}; items must be non-empty and every itemId "
                            + "must copy an item reference already returned by a Tool."),
            new Definition(
                    "recipe_grid",
                    BuiltinRichComponents::recipeGrid,
                    "Use to show one evidenced recipe. Exact properties: "
                            + "{\"sourceId\":string,\"generation\":string,\"recipeId\":string,"
                            + "\"label\":string|null}; copy the complete sourceId/generation/recipeId "
                            + "handle from one exact recipe Tool result."),
            new Definition(
                    "ingredient_check",
                    BuiltinRichComponents::ingredientCheck,
                    "Use to compare required and available evidenced items. Exact properties: "
                            + "{\"ingredients\":[{\"itemId\":string,\"required\":nonnegative integer,"
                            + "\"available\":nonnegative integer,\"label\":string|null}]}; ingredients "
                            + "must be non-empty and every itemId must already be Tool-evidenced."),
            new Definition(
                    "craftability_summary",
                    BuiltinRichComponents::craftability,
                    "Use only for a deterministic craftability Tool result. Exact properties: "
                            + "{\"sourceId\":string,\"generation\":string,\"recipeId\":string,"
                            + "\"craftable\":boolean,\"conclusive\":boolean,"
                            + "\"requestedCrafts\":positive integer,\"maximumCrafts\":nonnegative integer}; "
                            + "copy the recipe handle and all computed values unchanged from Tool evidence."),
            new Definition(
                    "progress_steps",
                    BuiltinRichComponents::progressSteps,
                    "Use for a short explanatory workflow, never as factual evidence. Exact properties: "
                            + "{\"steps\":[{\"id\":local-id,\"label\":string,"
                            + "\"state\":PENDING|ACTIVE|COMPLETE|FAILED}]}; steps must be non-empty "
                            + "and IDs unique."),
            new Definition(
                    "source_summary",
                    BuiltinRichComponents::sourceSummary,
                    "Use to summarize evidenced knowledge or recipe sources. Exact properties: "
                            + "{\"sources\":[{\"sourceId\":string,\"label\":string|null}]}; sources "
                            + "must be non-empty and every sourceId must copy a Tool-returned source reference."),
            new Definition(
                    "status_badge",
                    BuiltinRichComponents::statusBadge,
                    "Use for a concise display-only status. Exact properties: "
                            + "{\"state\":INFO|SUCCESS|WARNING|ERROR,\"label\":string}; "
                            + "the badge is not factual evidence."),
            new Definition(
                    "choice_group",
                    BuiltinRichComponents::choiceGroup,
                    "Use for inert suggested follow-ups only. Exact properties: "
                            + "{\"prompt\":string,\"choices\":[{\"id\":local-id,\"label\":string}]}; "
                            + "choices must be non-empty with unique IDs and cannot encode callbacks, commands, or URLs."));

    private BuiltinRichComponents() {}

    static Map<String, RichComponentRegistry.Decoder> decoders() {
        Map<String, RichComponentRegistry.Decoder> values = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            if (values.put(definition.type(), definition.decoder()) != null) {
                throw new IllegalStateException("duplicate built-in rich component " + definition.type());
            }
        }
        return Map.copyOf(values);
    }

    static List<PromptCatalogEntry> promptCatalog() {
        return DEFINITIONS.stream()
                .map(definition -> new PromptCatalogEntry(
                        definition.type(), definition.guidance()))
                .toList();
    }

    record PromptCatalogEntry(String type, String guidance) {
        PromptCatalogEntry {
            if (type == null || !type.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("prompt component type is invalid");
            }
            if (guidance == null || guidance.isBlank()) {
                throw new IllegalArgumentException("prompt component guidance is required");
            }
        }
    }

    private record Definition(
            String type,
            RichComponentRegistry.Decoder decoder,
            String guidance) {
        private Definition {
            java.util.Objects.requireNonNull(decoder, "decoder");
            new PromptCatalogEntry(type, guidance);
        }
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
