package dev.tomewisp.guide;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.regex.Pattern;

/** Closed, non-sensitive player projection of a Tool invocation. */
public final class GuideToolInvocationPresentation {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9_.-]+");

    private GuideToolInvocationPresentation() {}

    public static List<GuideToolMessage> messages(String toolId, JsonObject input) {
        String name = toolName(toolId);
        return switch (name) {
            case "resolve_resource" -> one(GuideToolMessage.Key.INVOCATION_RESOLVE_RESOURCE);
            case "search_recipes" -> optional(
                    GuideToolMessage.Key.INVOCATION_SEARCH_RECIPES,
                    GuideToolMessage.Key.INVOCATION_SEARCH_RECIPES_EXACT,
                    exactCriteria(input));
            case "get_recipe" -> optional(
                    GuideToolMessage.Key.INVOCATION_GET_RECIPE,
                    GuideToolMessage.Key.INVOCATION_GET_RECIPE_EXACT,
                    safeIdentifier(input, "recipeId"));
            case "find_item_usages" -> optional(
                    GuideToolMessage.Key.INVOCATION_FIND_USAGES,
                    GuideToolMessage.Key.INVOCATION_FIND_USAGES_EXACT,
                    safeIdentifier(input, "itemId"));
            case "inspect_inventory" -> one(GuideToolMessage.Key.INVOCATION_INSPECT_INVENTORY);
            case "calculate_craftability" -> one(GuideToolMessage.Key.INVOCATION_CRAFTABILITY);
            case "search_knowledge" -> one(GuideToolMessage.Key.INVOCATION_SEARCH_KNOWLEDGE);
            case "get_knowledge_document" -> one(GuideToolMessage.Key.INVOCATION_GET_KNOWLEDGE);
            case "list_knowledge_sources" -> one(
                    GuideToolMessage.Key.INVOCATION_LIST_KNOWLEDGE_SOURCES);
            case "get_patchouli_multiblock" -> one(GuideToolMessage.Key.INVOCATION_GET_MULTIBLOCK);
            case "load_skill" -> optional(
                    GuideToolMessage.Key.INVOCATION_LOAD_SKILL,
                    GuideToolMessage.Key.INVOCATION_LOAD_SKILL_EXACT,
                    safeName(input, "name"));
            case "platform_info", "player_context" -> one(
                    GuideToolMessage.Key.INVOCATION_GAME_ENVIRONMENT);
            case "inspect_game_state" -> optional(
                    GuideToolMessage.Key.INVOCATION_INSPECT_GAME_STATE,
                    GuideToolMessage.Key.INVOCATION_INSPECT_GAME_STATE_SECTION,
                    safeName(input, "section"));
            default -> one(GuideToolMessage.Key.INVOCATION_READ_ONLY);
        };
    }

    private static String exactCriteria(JsonObject input) {
        if (input == null) return "";
        for (String key : List.of("recipeId", "outputItem", "inputItem", "recipeType")) {
            String value = primitive(input, key);
            if (IDENTIFIER.matcher(value).matches()) return value;
        }
        return "";
    }

    private static String safeIdentifier(JsonObject input, String field) {
        String value = primitive(input, field);
        return IDENTIFIER.matcher(value).matches() ? value : "";
    }

    private static String safeName(JsonObject input, String field) {
        String value = primitive(input, field);
        return SAFE_NAME.matcher(value).matches() ? value : "";
    }

    private static String primitive(JsonObject input, String field) {
        return input != null && input.has(field) && input.get(field).isJsonPrimitive()
                ? input.get(field).getAsString()
                : "";
    }

    private static String toolName(String toolId) {
        int separator = toolId.indexOf(':');
        return separator < 0 ? toolId : toolId.substring(separator + 1);
    }

    private static List<GuideToolMessage> one(GuideToolMessage.Key key) {
        return List.of(GuideToolMessage.of(key));
    }

    private static List<GuideToolMessage> optional(
            GuideToolMessage.Key plain,
            GuideToolMessage.Key exact,
            String value) {
        return List.of(value.isBlank()
                ? GuideToolMessage.of(plain)
                : GuideToolMessage.of(exact, value));
    }
}
