package dev.openallay.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolUiReference;
import java.util.ArrayList;
import java.util.List;

/** Deterministic locale-independent player projection of a normalized Tool result. */
public final class GuideToolPresentation {
    private GuideToolPresentation() {}

    public static List<GuideToolMessage> resourceMessages(ToolUiReference reference) {
        java.util.Objects.requireNonNull(reference, "reference");
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(
                GuideToolMessage.Key.RESOURCE_RESULT_SUMMARY,
                reference.summary().operation(),
                Integer.toString(reference.summary().succeeded()),
                Integer.toString(reference.summary().failed())));
        if (reference.resultPath() != null) {
            messages.add(message(
                    GuideToolMessage.Key.RESOURCE_RESULT_PATH,
                    reference.resultPath().toString()));
        }
        if (reference.continuationAvailable()) {
            messages.add(message(GuideToolMessage.Key.RESOURCE_RESULT_CONTINUATION));
        }
        return List.copyOf(messages);
    }

    public static List<GuideToolMessage> messages(String toolId, JsonObject normalized) {
        if (normalized == null) return one(GuideToolMessage.Key.RESULT_PENDING);
        if (!"success".equals(string(normalized, "status"))) {
            return one(friendlyFailure(string(normalized, "code")));
        }
        JsonObject value = object(normalized, "value");
        if (value == null) return one(GuideToolMessage.Key.RESULT_VALUE_UNAVAILABLE);
        String name = toolId.substring(toolId.indexOf(':') + 1);
        return switch (name) {
            case "resource_list", "resource_read", "resource_glob", "resource_grep", "resource_query" ->
                    resourceResult(value);
            case "resolve_resource" -> resolvedResources(value);
            case "search_recipes" -> withCatalog(recipes(value), object(value, "catalog"));
            case "get_recipe" -> withCatalog(recipe(object(value, "recipe")), object(value, "catalog"));
            case "find_item_usages" -> withCatalog(usages(value), object(value, "catalog"));
            case "inspect_inventory" -> inventory(value);
            case "calculate_craftability" -> craftability(object(value, "result"));
            case "search_knowledge" -> knowledgeSearch(value);
            case "get_knowledge_document" -> knowledgeDocument(object(value, "document"));
            case "list_knowledge_sources" -> knowledgeSources(value);
            case "load_skill" -> loadedSkill(value);
            case "platform_info" -> platform(value);
            case "player_context" -> player(object(value, "player"));
            case "get_patchouli_multiblock" -> multiblock(object(value, "multiblock"));
            case "inspect_game_state" -> inspectedGameState(value);
            default -> one(GuideToolMessage.Key.RESULT_COMPLETED);
        };
    }

    private static List<GuideToolMessage> resourceResult(JsonObject value) {
        JsonArray items = array(value, "items");
        int succeeded = 0;
        for (JsonElement element : items) {
            if (element.isJsonObject()
                    && "success".equals(string(element.getAsJsonObject(), "status"))) {
                succeeded++;
            }
        }
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(
                GuideToolMessage.Key.RESOURCE_RESULT_SUMMARY,
                string(value, "operation"),
                Integer.toString(succeeded),
                Integer.toString(items.size() - succeeded)));
        String resultPath = string(value, "resultPath");
        if (!resultPath.isBlank()) {
            messages.add(message(GuideToolMessage.Key.RESOURCE_RESULT_PATH, resultPath));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> resolvedResources(JsonObject value) {
        JsonObject schema = object(value, "schema");
        if (schema != null) {
            return List.of(message(
                    GuideToolMessage.Key.RESOLVE_SCHEMA,
                    integer(array(schema, "fields").size()),
                    numberText(schema, "rows"),
                    string(schema, "dataset")));
        }
        JsonObject analysis = object(value, "analysis");
        if (analysis != null) {
            return List.of(message(
                    GuideToolMessage.Key.RESOLVE_ANALYSIS,
                    numberText(analysis, "sourceRows"),
                    integer(array(analysis, "stages").size()),
                    integer(array(analysis, "rows").size())));
        }
        JsonArray matches = array(value, "matches");
        if (matches.isEmpty()) return List.of(
                message(GuideToolMessage.Key.RESOLVE_NONE),
                message(GuideToolMessage.Key.RESOLVE_RETRY));
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(matches.size() == 1
                ? message(GuideToolMessage.Key.RESOLVE_ONE)
                : message(GuideToolMessage.Key.RESOLVE_MANY, integer(matches.size())));
        for (JsonElement element : matches) {
            JsonObject match = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.RESOLVE_MATCH,
                    string(match, "displayName"),
                    string(match, "id"),
                    string(match, "kind")));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> knowledgeSearch(JsonObject value) {
        JsonArray results = array(value, "results");
        JsonArray onlineResults = array(value, "onlineResults");
        if (results.isEmpty() && onlineResults.isEmpty()) return List.of(
                message(GuideToolMessage.Key.KNOWLEDGE_NONE),
                message(GuideToolMessage.Key.KNOWLEDGE_SCOPE));
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(
                GuideToolMessage.Key.KNOWLEDGE_COUNT,
                Integer.toString(results.size() + onlineResults.size())));
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.KNOWLEDGE_MATCH,
                    string(result, "title"), string(result, "sourceId")));
        }
        for (JsonElement element : onlineResults) {
            JsonObject result = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.KNOWLEDGE_MATCH,
                    string(result, "title"), string(result, "sourceId")));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> knowledgeDocument(JsonObject document) {
        if (document == null) return one(GuideToolMessage.Key.DOCUMENT_UNAVAILABLE);
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(GuideToolMessage.Key.DOCUMENT_LOADED, string(document, "title")));
        messages.add(message(
                GuideToolMessage.Key.DOCUMENT_SOURCE,
                string(document, "sourceId"), string(document, "kind")));
        if (!string(document, "structureRef").isBlank()) {
            messages.add(message(GuideToolMessage.Key.DOCUMENT_STRUCTURE));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> knowledgeSources(JsonObject value) {
        JsonArray sources = array(value, "sources");
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(sources.isEmpty()
                ? message(GuideToolMessage.Key.SOURCES_NONE)
                : message(GuideToolMessage.Key.SOURCES_COUNT, integer(sources.size())));
        for (JsonElement element : sources) {
            JsonObject source = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.SOURCE_ITEM,
                    string(source, "id"), numberText(source, "documents")));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> loadedSkill(JsonObject value) {
        JsonArray allowed = array(value, "allowedTools");
        return List.of(
                message(GuideToolMessage.Key.SKILL_LOADED, string(value, "name")),
                message(
                        GuideToolMessage.Key.SKILL_TOOLS,
                        integer(allowed.size()), string(value, "provenance")));
    }

    private static List<GuideToolMessage> platform(JsonObject value) {
        return List.of(
                message(
                        GuideToolMessage.Key.PLATFORM,
                        string(value, "gameVersion"), string(value, "platform")),
                message(bool(value, "developmentEnvironment")
                        ? GuideToolMessage.Key.ENVIRONMENT_DEVELOPMENT
                        : GuideToolMessage.Key.ENVIRONMENT_NORMAL));
    }

    private static List<GuideToolMessage> player(JsonObject player) {
        if (player == null) return one(GuideToolMessage.Key.PLAYER_UNAVAILABLE);
        JsonObject position = object(player, "position");
        JsonObject inventory = object(player, "inventory");
        JsonArray slots = array(inventory, "slots");
        return List.of(
                message(
                        GuideToolMessage.Key.PLAYER_IDENTITY,
                        string(player, "dimension"), string(player, "gameMode")),
                position == null
                        ? message(GuideToolMessage.Key.POSITION_UNAVAILABLE)
                        : message(
                                GuideToolMessage.Key.POSITION,
                                numberText(position, "x"),
                                numberText(position, "y"),
                                numberText(position, "z")),
                message(GuideToolMessage.Key.PLAYER_INVENTORY_SLOTS, integer(slots.size())));
    }

    private static List<GuideToolMessage> multiblock(JsonObject value) {
        if (value == null) return one(GuideToolMessage.Key.MULTIBLOCK_UNAVAILABLE);
        JsonArray blocks = array(value, "blocks");
        return List.of(
                message(GuideToolMessage.Key.MULTIBLOCK_VERIFIED),
                message(GuideToolMessage.Key.MULTIBLOCK_BLOCKS, integer(blocks.size())),
                message(GuideToolMessage.Key.MULTIBLOCK_NOT_WORLD_SCAN));
    }

    private static List<GuideToolMessage> inspectedGameState(JsonObject value) {
        String section = string(value, "section");
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(section.isBlank()
                ? message(GuideToolMessage.Key.GAME_STATE_GENERIC)
                : message(GuideToolMessage.Key.GAME_STATE_SECTION, section));
        JsonObject selected = switch (section) {
            case "OVERVIEW" -> object(value, "overview");
            case "MODS" -> object(value, "mods");
            case "OPTIONS" -> object(value, "options");
            case "PACKS" -> object(value, "packs");
            case "SHADERS" -> object(value, "shaders");
            case "DIAGNOSTICS" -> object(value, "diagnostics");
            case "PLAYER" -> object(value, "player");
            case "WORLD_QUERY" -> object(value, "worldQuery");
            default -> null;
        };
        if (selected != null) {
            switch (section) {
                case "OVERVIEW" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_OVERVIEW,
                        string(selected, "gameVersion"),
                        string(selected, "loader"),
                        string(selected, "connectionKind")));
                case "MODS" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_MODS,
                        integer(array(selected, "installed").size())));
                case "OPTIONS" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_OPTIONS,
                        integer(array(selected, "values").size())));
                case "PACKS" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_PACKS,
                        integer(array(selected, "resourcePacks").size()),
                        integer(array(selected, "visibleDataPacks").size())));
                case "SHADERS" -> messages.add(bool(selected, "integrationAvailable")
                        ? message(
                                GuideToolMessage.Key.GAME_STATE_SHADER_PROVIDER,
                                string(selected, "provider"))
                        : message(GuideToolMessage.Key.GAME_STATE_SHADER_UNAVAILABLE));
                case "DIAGNOSTICS" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_DIAGNOSTICS,
                        integer(array(selected, "values").size())));
                case "PLAYER" -> {
                    JsonObject inventory = object(selected, "inventory");
                    if (inventory != null) {
                        messages.add(message(
                                GuideToolMessage.Key.GAME_STATE_PLAYER_INVENTORY,
                                integer(array(inventory, "slots").size())));
                    } else if (!string(selected, "openScreen").isBlank()) {
                        messages.add(message(
                                GuideToolMessage.Key.GAME_STATE_PLAYER_SCREEN,
                                string(selected, "openScreen")));
                    } else {
                        messages.add(message(
                                GuideToolMessage.Key.PLAYER_IDENTITY,
                                string(selected, "dimension"), string(selected, "gameMode")));
                    }
                }
                case "WORLD_QUERY" -> messages.add(message(
                        GuideToolMessage.Key.GAME_STATE_WORLD_QUERY,
                        integer(objectSize(selected, "values"))));
                default -> { }
            }
        }
        JsonArray diagnostics = selected == null ? new JsonArray() : array(selected, "diagnostics");
        if (!diagnostics.isEmpty()) {
            messages.add(message(
                    GuideToolMessage.Key.GAME_STATE_PARTIAL,
                    integer(diagnostics.size())));
        }
        return List.copyOf(messages);
    }

    private static int objectSize(JsonObject value, String field) {
        JsonObject object = object(value, field);
        return object == null ? 0 : object.size();
    }

    private static List<GuideToolMessage> usages(JsonObject value) {
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(GuideToolMessage.Key.USAGES_HEADER, string(value, "itemId")));
        for (JsonElement element : array(value, "usages")) {
            JsonObject usage = element.getAsJsonObject();
            JsonObject reference = object(usage, "reference");
            GuideToolMessage.Key key = switch (string(usage, "role")) {
                case "INPUT" -> GuideToolMessage.Key.USAGE_INPUT;
                case "CATALYST" -> GuideToolMessage.Key.USAGE_CATALYST;
                case "OUTPUT" -> GuideToolMessage.Key.USAGE_OUTPUT;
                case "BYPRODUCT" -> GuideToolMessage.Key.USAGE_BYPRODUCT;
                default -> GuideToolMessage.Key.USAGE_RELATED;
            };
            messages.add(message(key, string(reference, "recipeId")));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> withCatalog(
            List<GuideToolMessage> content, JsonObject catalog) {
        if (catalog == null) return content;
        List<GuideToolMessage> messages = new ArrayList<>(content);
        boolean partial = !"COMPLETE".equals(string(catalog, "completeness"));
        for (JsonElement element : array(catalog, "providers")) {
            JsonObject provider = element.getAsJsonObject();
            partial |= !"AVAILABLE".equals(string(provider, "state"))
                    || !"COMPLETE".equals(string(provider, "completeness"));
        }
        if (partial) messages.add(message(GuideToolMessage.Key.CATALOG_PARTIAL));
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> recipes(JsonObject value) {
        JsonArray recipes = array(value, "recipes");
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(recipes.isEmpty()
                ? message(GuideToolMessage.Key.RECIPES_NONE)
                : message(GuideToolMessage.Key.RECIPES_COUNT, integer(recipes.size())));
        for (JsonElement element : recipes) {
            JsonObject recipe = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.RECIPE_ITEM,
                    primaryOutputLabel(recipe)));
            messages.addAll(outputs(
                    array(recipe, "outputs"), GuideToolMessage.Key.RECIPE_OUTPUT));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> recipe(JsonObject recipe) {
        if (recipe == null) return one(GuideToolMessage.Key.RECIPE_UNAVAILABLE);
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(
                GuideToolMessage.Key.RECIPE_DETAIL,
                primaryOutputLabel(recipe)));
        JsonArray ingredients = array(recipe, "ingredients");
        messages.add(message(
                GuideToolMessage.Key.INGREDIENT_GROUPS, integer(ingredients.size())));
        for (JsonElement element : ingredients) {
            JsonObject ingredient = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.INGREDIENT,
                    string(ingredient, "key"), numberText(ingredient, "count")));
        }
        messages.addAll(outputs(
                array(recipe, "outputs"), GuideToolMessage.Key.RECIPE_OUTPUT));
        messages.addAll(outputs(
                array(recipe, "byproducts"), GuideToolMessage.Key.RECIPE_BYPRODUCT));
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> inventory(JsonObject value) {
        JsonObject counts = object(value, "counts");
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(
                GuideToolMessage.Key.INVENTORY_KINDS,
                integer(counts == null ? 0 : counts.size())));
        if (counts != null) {
            counts.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> messages.add(message(
                            GuideToolMessage.Key.INVENTORY_ITEM,
                            entry.getKey(), Long.toString(entry.getValue().getAsLong()))));
        }
        JsonObject inventory = object(value, "inventory");
        if (inventory != null && !bool(inventory, "complete")) {
            messages.add(message(GuideToolMessage.Key.INVENTORY_PARTIAL));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> craftability(JsonObject result) {
        if (result == null) return one(GuideToolMessage.Key.CRAFTABILITY_UNAVAILABLE);
        List<GuideToolMessage> messages = new ArrayList<>();
        messages.add(message(bool(result, "craftable")
                ? GuideToolMessage.Key.CRAFTABILITY_READY
                : GuideToolMessage.Key.CRAFTABILITY_NOT_READY));
        if (!bool(result, "conclusive")) {
            messages.add(message(GuideToolMessage.Key.CRAFTABILITY_INCONCLUSIVE));
        }
        messages.add(message(
                GuideToolMessage.Key.CRAFTABILITY_SUMMARY,
                numberText(result, "requestedCrafts"), numberText(result, "maximumCrafts")));
        for (JsonElement element : array(result, "allocations")) {
            JsonObject allocation = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.CRAFTABILITY_ALLOCATION,
                    string(allocation, "itemId"),
                    numberText(allocation, "count"),
                    string(allocation, "requirementKey")));
        }
        for (JsonElement element : array(result, "missing")) {
            JsonObject missing = element.getAsJsonObject();
            messages.add(message(
                    GuideToolMessage.Key.CRAFTABILITY_MISSING,
                    string(missing, "requirementKey"), numberText(missing, "missing")));
        }
        return List.copyOf(messages);
    }

    private static List<GuideToolMessage> outputs(
            JsonArray values, GuideToolMessage.Key key) {
        List<GuideToolMessage> messages = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject output = element.getAsJsonObject();
            JsonObject stack = object(output, "stack");
            if (stack != null) {
                messages.add(message(
                        key, string(stack, "itemId"), numberText(stack, "count")));
            }
        }
        return messages;
    }

    private static String primaryOutputLabel(JsonObject recipe) {
        JsonArray outputs = array(recipe, "outputs");
        if (!outputs.isEmpty()) {
            JsonObject stack = object(outputs.get(0).getAsJsonObject(), "stack");
            if (stack != null) {
                String displayName = string(stack, "displayName");
                if (!displayName.isBlank()) return displayName;
                String itemId = string(stack, "itemId");
                if (!itemId.isBlank()) return itemId;
            }
        }
        JsonObject reference = object(recipe, "reference");
        String recipeId = string(reference, "recipeId");
        return recipeId.isBlank() ? "recipe" : recipeId;
    }

    private static GuideToolMessage.Key friendlyFailure(String code) {
        return switch (code) {
            case "stale_reference" -> GuideToolMessage.Key.FAILURE_STALE_REFERENCE;
            case "capability_unavailable", "tool_unavailable" ->
                    GuideToolMessage.Key.FAILURE_UNAVAILABLE;
            case "player_required" -> GuideToolMessage.Key.FAILURE_PLAYER_REQUIRED;
            case "invalid_arguments" -> GuideToolMessage.Key.FAILURE_INVALID_ARGUMENTS;
            case "unauthorized", "forbidden" -> GuideToolMessage.Key.FAILURE_FORBIDDEN;
            default -> GuideToolMessage.Key.FAILURE_GENERIC;
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

    private static String numberText(JsonObject value, String field) {
        return Long.toString(value != null && value.has(field) ? value.get(field).getAsLong() : 0);
    }

    private static boolean bool(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).getAsBoolean();
    }

    private static String integer(int value) {
        return Integer.toString(value);
    }

    private static List<GuideToolMessage> one(GuideToolMessage.Key key) {
        return List.of(message(key));
    }

    private static GuideToolMessage message(
            GuideToolMessage.Key key, String... arguments) {
        String[] safe = new String[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            safe[index] = safe(arguments[index]);
        }
        return GuideToolMessage.of(key, safe);
    }

    private static String safe(String value) {
        StringBuilder safe = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) safe.append(' ');
            else safe.appendCodePoint(codePoint);
        });
        return safe.toString().strip();
    }
}
