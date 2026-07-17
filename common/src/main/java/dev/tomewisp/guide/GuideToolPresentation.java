package dev.tomewisp.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Deterministic player-visible projection of a normalized tool result. */
public final class GuideToolPresentation {
    private GuideToolPresentation() {}

    public static List<String> lines(String toolId, JsonObject normalized) {
        if (normalized == null) return List.of("尚无结果");
        if (!"success".equals(string(normalized, "status"))) {
            return List.of("失败: " + string(normalized, "code"), string(normalized, "message"));
        }
        JsonObject value = object(normalized, "value");
        if (value == null) return List.of(normalized.toString());
        String name = toolId.substring(toolId.indexOf(':') + 1);
        return switch (name) {
            case "search_recipes" -> recipes(value);
            case "get_recipe" -> recipe(object(value, "recipe"));
            case "inspect_inventory" -> inventory(value);
            case "calculate_craftability" -> craftability(object(value, "result"));
            default -> List.of(value.toString());
        };
    }

    private static List<String> recipes(JsonObject value) {
        JsonArray recipes = array(value, "recipes");
        List<String> lines = new ArrayList<>();
        lines.add("候选配方: " + recipes.size());
        for (JsonElement element : recipes) {
            JsonObject recipe = element.getAsJsonObject();
            JsonObject reference = object(recipe, "reference");
            lines.add("• " + string(reference, "recipeId") + " · " + string(recipe, "type")
                    + workstation(recipe));
            lines.addAll(outputs(array(recipe, "outputs"), "  产出 "));
        }
        return List.copyOf(lines);
    }

    private static List<String> recipe(JsonObject recipe) {
        if (recipe == null) return List.of("配方详情不可用");
        List<String> lines = new ArrayList<>();
        JsonObject reference = object(recipe, "reference");
        lines.add("配方: " + string(reference, "recipeId"));
        lines.add("类型: " + string(recipe, "type") + workstation(recipe));
        JsonArray ingredients = array(recipe, "ingredients");
        lines.add("材料组: " + ingredients.size());
        for (JsonElement element : ingredients) {
            JsonObject ingredient = element.getAsJsonObject();
            lines.add("• " + string(ingredient, "key") + " × " + number(ingredient, "count")
                    + "，候选 " + array(ingredient, "alternatives").size());
        }
        lines.addAll(outputs(array(recipe, "outputs"), "产出 "));
        lines.addAll(outputs(array(recipe, "byproducts"), "副产物 "));
        return List.copyOf(lines);
    }

    private static List<String> inventory(JsonObject value) {
        JsonObject counts = object(value, "counts");
        List<String> lines = new ArrayList<>();
        lines.add("物品种类: " + (counts == null ? 0 : counts.size()));
        if (counts != null) {
            counts.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add("• " + entry.getKey() + " × " + entry.getValue().getAsLong()));
        }
        JsonObject inventory = object(value, "inventory");
        if (inventory != null) lines.add("库存完整: " + bool(inventory, "complete"));
        return List.copyOf(lines);
    }

    private static List<String> craftability(JsonObject result) {
        if (result == null) return List.of("可合成性结果不可用");
        List<String> lines = new ArrayList<>();
        lines.add("可合成: " + bool(result, "craftable") + "；结论完备: " + bool(result, "conclusive"));
        lines.add("请求次数: " + number(result, "requestedCrafts")
                + "；当前最多: " + number(result, "maximumCrafts"));
        for (JsonElement element : array(result, "allocations")) {
            JsonObject allocation = element.getAsJsonObject();
            lines.add("✓ " + string(allocation, "itemId") + " × " + number(allocation, "count")
                    + " → " + string(allocation, "requirementKey"));
        }
        for (JsonElement element : array(result, "missing")) {
            JsonObject missing = element.getAsJsonObject();
            lines.add("缺少 " + string(missing, "requirementKey") + " × " + number(missing, "missing"));
        }
        return List.copyOf(lines);
    }

    private static List<String> outputs(JsonArray values, String prefix) {
        List<String> lines = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject output = element.getAsJsonObject();
            JsonObject stack = object(output, "stack");
            if (stack != null) lines.add(prefix + string(stack, "itemId") + " × " + number(stack, "count"));
        }
        return lines;
    }

    private static String workstation(JsonObject recipe) {
        String value = string(recipe, "workstation");
        return value.isBlank() ? "" : " · " + value;
    }

    private static JsonObject object(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonObject()
                ? value.getAsJsonObject(field) : null;
    }

    private static JsonArray array(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonArray()
                ? value.getAsJsonArray(field) : new JsonArray();
    }

    private static String string(JsonObject value, String field) {
        return value != null && value.has(field) && !value.get(field).isJsonNull()
                ? value.get(field).getAsString() : "";
    }

    private static long number(JsonObject value, String field) {
        return value != null && value.has(field) ? value.get(field).getAsLong() : 0;
    }

    private static boolean bool(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).getAsBoolean();
    }
}
