package dev.tomewisp.guide.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tomewisp.context.RecipeReference;
import java.util.ArrayList;
import java.util.List;

/** Strictly derives native recipe cards from normalized grounded tool results. */
public final class GuideRecipePresenter {
    private GuideRecipePresenter() {}

    public static List<GuideRecipeCard> cards(String toolId, JsonObject normalized) {
        if (normalized == null || !"success".equals(string(normalized, "status"))) {
            return List.of();
        }
        JsonObject value = object(normalized, "value");
        if (value == null) {
            return List.of();
        }
        String name = toolId.substring(toolId.indexOf(':') + 1);
        List<JsonObject> recipes = switch (name) {
            case "search_recipes" -> objects(array(value, "recipes"));
            case "get_recipe" -> {
                JsonObject recipe = object(value, "recipe");
                yield recipe == null ? List.of() : List.of(recipe);
            }
            default -> List.of();
        };
        List<GuideRecipeCard> cards = new ArrayList<>();
        for (JsonObject recipe : recipes) {
            try {
                GuideRecipeCard card = card(recipe);
                if (!card.outputs().isEmpty()) {
                    cards.add(card);
                }
            } catch (RuntimeException ignored) {
                // Invalid semantic data remains available in the text presentation only.
            }
        }
        return List.copyOf(cards);
    }

    private static GuideRecipeCard card(JsonObject recipe) {
        JsonObject reference = requiredObject(recipe, "reference");
        RecipeReference parsed = new RecipeReference(
                requiredString(reference, "sourceId"),
                requiredString(reference, "generation"),
                requiredString(reference, "recipeId"));
        List<GuideRecipeCard.Output> outputs = new ArrayList<>();
        outputs.addAll(outputs(recipe, "outputs"));
        return new GuideRecipeCard(
                parsed,
                references(recipe, parsed),
                requiredString(recipe, "id"),
                requiredString(recipe, "type"),
                string(recipe, "workstation"),
                outputs,
                ingredients(recipe, "ingredients"),
                ingredients(recipe, "catalysts"),
                outputs(recipe, "byproducts"),
                processing(recipe));
    }

    private static List<GuideRecipeCard.Ingredient> ingredients(JsonObject recipe, String field) {
        List<GuideRecipeCard.Ingredient> ingredients = new ArrayList<>();
        for (JsonElement element : array(recipe, field)) {
            JsonObject ingredient = requiredObject(element, "recipe ingredient");
            List<GuideRecipeCard.Alternative> alternatives = new ArrayList<>();
            for (JsonElement encoded : array(ingredient, "alternatives")) {
                JsonObject alternative = requiredObject(encoded, "ingredient alternative");
                List<String> resolvedItems = new ArrayList<>();
                for (JsonElement item : array(alternative, "resolvedItems")) {
                    resolvedItems.add(item.getAsString());
                }
                alternatives.add(new GuideRecipeCard.Alternative(
                        requiredString(alternative, "kind"),
                        requiredString(alternative, "id"),
                        resolvedItems));
            }
            ingredients.add(new GuideRecipeCard.Ingredient(
                    requiredString(ingredient, "key"),
                    positiveLong(ingredient, "count"),
                    bool(ingredient, "consumed", true),
                    alternatives));
        }
        return List.copyOf(ingredients);
    }

    private static List<GuideRecipeCard.Output> outputs(JsonObject recipe, String field) {
        List<GuideRecipeCard.Output> outputs = new ArrayList<>();
        for (JsonElement element : array(recipe, field)) {
            JsonObject output = requiredObject(element, "recipe output");
            JsonObject stack = requiredObject(output, "stack");
            outputs.add(new GuideRecipeCard.Output(
                    requiredString(stack, "itemId"),
                    positiveInteger(stack, "count"),
                    string(stack, "displayName")));
        }
        return List.copyOf(outputs);
    }

    private static GuideRecipeCard.Processing processing(JsonObject recipe) {
        JsonObject processing = object(recipe, "processing");
        if (processing == null) return GuideRecipeCard.Processing.unknown();
        return new GuideRecipeCard.Processing(
                nullableLong(processing, "durationTicks"),
                nullableLong(processing, "energy"),
                nullableDouble(processing, "temperature"));
    }

    private static List<RecipeReference> references(
            JsonObject recipe, RecipeReference primary) {
        JsonArray encoded = array(recipe, "references");
        if (encoded.isEmpty()) {
            return List.of(primary);
        }
        List<RecipeReference> references = new ArrayList<>();
        for (JsonElement element : encoded) {
            JsonObject reference = requiredObject(element, "recipe reference");
            references.add(new RecipeReference(
                    requiredString(reference, "sourceId"),
                    requiredString(reference, "generation"),
                    requiredString(reference, "recipeId")));
        }
        if (!references.contains(primary)) {
            references.addFirst(primary);
        }
        return List.copyOf(references);
    }

    private static List<JsonObject> objects(JsonArray array) {
        List<JsonObject> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                values.add(element.getAsJsonObject());
            }
        }
        return values;
    }

    private static int positiveInteger(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        int result = value.getAsInt();
        if (result <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return result;
    }

    private static long positiveLong(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        long result = value.getAsLong();
        if (result <= 0) throw new IllegalArgumentException(field + " must be positive");
        return result;
    }

    private static Long nullableLong(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    private static Double nullableDouble(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsDouble();
    }

    private static boolean bool(JsonObject object, String field, boolean fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static JsonObject requiredObject(JsonObject value, String field) {
        JsonObject result = object(value, field);
        if (result == null) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return result;
    }

    private static JsonObject requiredObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonObject object(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonObject()
                ? value.getAsJsonObject(field) : null;
    }

    private static JsonArray array(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonArray()
                ? value.getAsJsonArray(field) : new JsonArray();
    }

    private static String requiredString(JsonObject value, String field) {
        String result = string(value, field);
        if (result.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return result;
    }

    private static String string(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonPrimitive()
                && value.get(field).getAsJsonPrimitive().isString()
                ? value.get(field).getAsString() : "";
    }
}
