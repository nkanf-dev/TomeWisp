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
        if (normalized == null) return List.of("正在获取结果……");
        if (!"success".equals(string(normalized, "status"))) {
            return List.of(friendlyFailure(string(normalized, "code")));
        }
        JsonObject value = object(normalized, "value");
        if (value == null) return List.of("结果暂时无法显示。");
        String name = toolId.substring(toolId.indexOf(':') + 1);
        return switch (name) {
            case "search_recipes" -> withCatalog(recipes(value), object(value, "catalog"));
            case "get_recipe" -> withCatalog(recipe(object(value, "recipe")), object(value, "catalog"));
            case "find_item_usages" -> withCatalog(usages(value), object(value, "catalog"));
            case "inspect_inventory" -> inventory(value);
            case "calculate_craftability" -> craftability(object(value, "result"));
            default -> List.of("这个工具已经完成。");
        };
    }

    private static List<String> usages(JsonObject value) {
        List<String> lines = new ArrayList<>();
        lines.add("物品用途: " + string(value, "itemId"));
        for (JsonElement element : array(value, "usages")) {
            JsonObject usage = element.getAsJsonObject();
            JsonObject reference = object(usage, "reference");
            lines.add("• " + friendlyRole(string(usage, "role")) + " · "
                    + string(reference, "recipeId"));
        }
        return List.copyOf(lines);
    }

    private static List<String> withCatalog(List<String> content, JsonObject catalog) {
        if (catalog == null) return content;
        List<String> lines = new ArrayList<>(content);
        boolean partial = !"COMPLETE".equals(string(catalog, "completeness"));
        for (JsonElement element : array(catalog, "providers")) {
            JsonObject provider = element.getAsJsonObject();
            partial |= !"AVAILABLE".equals(string(provider, "state"))
                    || !"COMPLETE".equals(string(provider, "completeness"));
        }
        if (partial) lines.add("部分配方来源当前不可用，结果可能不完整。");
        return List.copyOf(lines);
    }

    private static List<String> recipes(JsonObject value) {
        JsonArray recipes = array(value, "recipes");
        List<String> lines = new ArrayList<>();
        lines.add(recipes.isEmpty() ? "没有找到匹配的配方。" : "找到 " + recipes.size() + " 个配方：");
        for (JsonElement element : recipes) {
            JsonObject recipe = element.getAsJsonObject();
            JsonObject reference = object(recipe, "reference");
            lines.add("• " + string(reference, "recipeId") + workstation(recipe));
            lines.addAll(outputs(array(recipe, "outputs"), "  产出 "));
        }
        return List.copyOf(lines);
    }

    private static List<String> recipe(JsonObject recipe) {
        if (recipe == null) return List.of("配方详情不可用");
        List<String> lines = new ArrayList<>();
        JsonObject reference = object(recipe, "reference");
        lines.add("配方: " + string(reference, "recipeId") + workstation(recipe));
        JsonArray ingredients = array(recipe, "ingredients");
        lines.add("材料组: " + ingredients.size());
        for (JsonElement element : ingredients) {
            JsonObject ingredient = element.getAsJsonObject();
            lines.add("• " + string(ingredient, "key") + " × " + number(ingredient, "count"));
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
        if (inventory != null && !bool(inventory, "complete")) {
            lines.add("背包信息可能不完整。");
        }
        return List.copyOf(lines);
    }

    private static List<String> craftability(JsonObject result) {
        if (result == null) return List.of("可合成性结果不可用");
        List<String> lines = new ArrayList<>();
        lines.add(bool(result, "craftable") ? "材料已经备齐。" : "材料还没有备齐。");
        if (!bool(result, "conclusive")) lines.add("现有信息不足，结果仅供参考。");
        lines.add("计划制作 " + number(result, "requestedCrafts")
                + " 次；当前最多可制作 " + number(result, "maximumCrafts") + " 次。");
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

    private static String friendlyRole(String role) {
        return switch (role) {
            case "INPUT" -> "作为材料";
            case "CATALYST" -> "作为工具或催化剂";
            case "OUTPUT" -> "作为产物";
            case "BYPRODUCT" -> "作为副产物";
            default -> "相关配方";
        };
    }

    private static String friendlyFailure(String code) {
        return switch (code) {
            case "stale_reference" -> "这个结果已经过期，请重新查询。";
            case "capability_unavailable", "tool_unavailable" -> "当前环境暂时无法提供这项信息。";
            case "player_required" -> "需要进入游戏世界后才能查看这项信息。";
            case "invalid_arguments" -> "查询条件不完整，请换一种说法再试。";
            case "unauthorized", "forbidden" -> "当前服务器不允许查看这项信息。";
            default -> "这次查询没有成功，请稍后重试。";
        };
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
