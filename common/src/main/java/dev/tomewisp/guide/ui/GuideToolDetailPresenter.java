package dev.tomewisp.guide.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolPresentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts normalized tool activity into the closed player-card vocabulary. */
public final class GuideToolDetailPresenter {
    private GuideToolDetailPresenter() {}

    public static GuideToolDetailView project(GuideToolActivity activity, boolean debugMode) {
        JsonObject normalized = activity.normalized();
        Projection projection = projectCards(activity.toolId(), normalized);
        List<String> narration = GuideToolPresentation.lines(activity.toolId(), normalized);
        Optional<GuideToolDetailView.Debug> debug = debugMode
                ? Optional.of(new GuideToolDetailView.Debug(
                        activity.invocationId(),
                        activity.toolId(),
                        activity.sources(),
                        normalized,
                        projection.diagnostic()))
                : Optional.empty();
        return new GuideToolDetailView(
                titleKey(activity.toolId()),
                activity.status(),
                projection.cards(),
                narration,
                debug);
    }

    private static Projection projectCards(String toolId, JsonObject normalized) {
        if (normalized == null) {
            return text("screen.tomewisp.detail.status", "正在获取结果……", "result unavailable");
        }
        if (!"success".equals(string(normalized, "status"))) {
            return new Projection(
                    List.of(new GuideDetailCard.Error(friendlyFailure(string(normalized, "code")))),
                    "tool returned a normalized failure");
        }
        JsonObject value = object(normalized, "value");
        if (value == null) {
            return text("screen.tomewisp.detail.result", "结果暂时无法显示。", "value is missing");
        }
        String name = toolName(toolId);
        try {
            return switch (name) {
                case "search_recipes", "get_recipe" -> recipeCards(toolId, normalized);
                case "find_item_usages" -> usageCards(value);
                case "inspect_inventory" -> inventoryCards(value);
                case "calculate_craftability" -> craftabilityCards(value);
                default -> text("screen.tomewisp.detail.result", "这个工具已经完成。", "unknown tool projection");
            };
        } catch (RuntimeException exception) {
            return text("screen.tomewisp.detail.result", "结果已返回，但其中一部分暂时无法显示。",
                    "malformed semantic result");
        }
    }

    private static Projection recipeCards(String toolId, JsonObject normalized) {
        List<GuideDetailCard> cards = GuideRecipePresenter.cards(toolId, normalized).stream()
                .<GuideDetailCard>map(GuideDetailCard.Recipe::new)
                .toList();
        if (!cards.isEmpty()) {
            return new Projection(cards, "");
        }
        JsonObject value = object(normalized, "value");
        boolean hasRecipeData = "search_recipes".equals(toolName(toolId))
                ? !array(value, "recipes").isEmpty()
                : object(value, "recipe") != null;
        return text(
                "screen.tomewisp.detail.recipes",
                hasRecipeData ? "配方结果暂时无法显示。" : "没有找到匹配的配方。",
                hasRecipeData ? "invalid recipe card data" : "");
    }

    private static Projection inventoryCards(JsonObject value) {
        JsonObject counts = object(value, "counts");
        if (counts == null || counts.isEmpty()) {
            return text("screen.tomewisp.detail.inventory", "背包里没有可显示的物品。", "");
        }
        List<GuideItemView> items = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new GuideItemView(entry.getKey(), entry.getKey(), nonnegativeLong(entry.getValue())))
                .filter(item -> item.count() > 0)
                .toList();
        return items.isEmpty()
                ? text("screen.tomewisp.detail.inventory", "背包里没有可显示的物品。", "")
                : new Projection(List.of(new GuideDetailCard.ItemGrid(
                        "screen.tomewisp.detail.inventory", items)), "");
    }

    private static Projection usageCards(JsonObject value) {
        JsonArray usages = array(value, "usages");
        if (usages.isEmpty()) {
            return text("screen.tomewisp.detail.usages", "没有找到相关用途。", "");
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement element : usages) {
            JsonObject usage = requireObject(element, "usage");
            JsonObject reference = requireObject(usage, "reference");
            lines.add(role(string(usage, "role")) + " · " + requiredString(reference, "recipeId"));
        }
        return new Projection(List.of(new GuideDetailCard.Text(
                "screen.tomewisp.detail.usages", lines)), "");
    }

    private static Projection craftabilityCards(JsonObject value) {
        JsonObject result = requireObject(value, "result");
        Map<String, RequirementBuilder> requirements = new LinkedHashMap<>();
        for (JsonElement element : array(result, "allocations")) {
            JsonObject allocation = requireObject(element, "allocation");
            String key = requiredString(allocation, "requirementKey");
            long count = positiveLong(allocation, "count");
            requirements.computeIfAbsent(key, RequirementBuilder::new)
                    .allocatedItems.add(new GuideItemView(
                            requiredString(allocation, "itemId"),
                            requiredString(allocation, "itemId"),
                            count));
        }
        for (JsonElement element : array(result, "missing")) {
            JsonObject missing = requireObject(element, "missing requirement");
            String key = requiredString(missing, "requirementKey");
            RequirementBuilder builder = requirements.computeIfAbsent(key, RequirementBuilder::new);
            builder.required = positiveLong(missing, "required");
            builder.allocated = nonnegativeLong(missing.get("allocated"));
            builder.missing = positiveLong(missing, "missing");
            for (JsonElement alternative : array(missing, "alternatives")) {
                String itemId = alternative.getAsString();
                builder.alternatives.add(new GuideItemView(itemId, itemId, 0));
            }
        }
        List<GuideDetailCard.Requirement> rows = new ArrayList<>();
        for (RequirementBuilder builder : requirements.values()) {
            long itemTotal = builder.allocatedItems.stream().mapToLong(GuideItemView::count).sum();
            long allocated = builder.required == 0 ? itemTotal : builder.allocated;
            long required = builder.required == 0 ? itemTotal : builder.required;
            rows.add(new GuideDetailCard.Requirement(
                    builder.key,
                    required,
                    allocated,
                    builder.missing,
                    builder.allocatedItems,
                    builder.alternatives));
        }
        return new Projection(List.of(new GuideDetailCard.Requirements(
                bool(result, "craftable"),
                bool(result, "conclusive"),
                positiveLong(result, "requestedCrafts"),
                nonnegativeLong(result.get("maximumCrafts")),
                rows)), "");
    }

    private static Projection text(String titleKey, String line, String diagnostic) {
        return new Projection(List.of(new GuideDetailCard.Text(titleKey, List.of(line))), diagnostic);
    }

    private static String titleKey(String toolId) {
        return switch (toolName(toolId)) {
            case "search_recipes" -> "screen.tomewisp.tool.search_recipes";
            case "get_recipe" -> "screen.tomewisp.tool.get_recipe";
            case "find_item_usages" -> "screen.tomewisp.tool.find_item_usages";
            case "inspect_inventory" -> "screen.tomewisp.tool.inspect_inventory";
            case "calculate_craftability" -> "screen.tomewisp.tool.calculate_craftability";
            default -> "screen.tomewisp.tool.result";
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

    private static String role(String role) {
        return switch (role) {
            case "INPUT" -> "作为材料";
            case "CATALYST" -> "作为工具或催化剂";
            case "OUTPUT" -> "作为产物";
            case "BYPRODUCT" -> "作为副产物";
            default -> "相关配方";
        };
    }

    private static String toolName(String toolId) {
        int separator = toolId.indexOf(':');
        return separator < 0 ? toolId : toolId.substring(separator + 1);
    }

    private static JsonObject requireObject(JsonObject value, String field) {
        JsonObject result = object(value, field);
        if (result == null) throw new IllegalArgumentException(field + " must be an object");
        return result;
    }

    private static JsonObject requireObject(JsonElement value, String label) {
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
        if (result.isBlank()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String string(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonPrimitive()
                ? value.get(field).getAsString() : "";
    }

    private static boolean bool(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).getAsBoolean();
    }

    private static long positiveLong(JsonObject value, String field) {
        long result = nonnegativeLong(value.get(field));
        if (result <= 0) throw new IllegalArgumentException(field + " must be positive");
        return result;
    }

    private static long nonnegativeLong(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("count must be numeric");
        }
        long result = value.getAsLong();
        if (result < 0) throw new IllegalArgumentException("count must not be negative");
        return result;
    }

    private record Projection(List<GuideDetailCard> cards, String diagnostic) {
        private Projection {
            cards = List.copyOf(cards);
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private static final class RequirementBuilder {
        private final String key;
        private long required;
        private long allocated;
        private long missing;
        private final List<GuideItemView> allocatedItems = new ArrayList<>();
        private final List<GuideItemView> alternatives = new ArrayList<>();

        private RequirementBuilder(String key) {
            this.key = key;
        }
    }
}
