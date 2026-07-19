package dev.tomewisp.guide;

import java.util.List;
import java.util.Objects;

/** Closed locale-independent Tool presentation message safe for history and bridge transport. */
public record GuideToolMessage(Key key, List<String> arguments) {
    public enum Key {
        INVOCATION_RESOLVE_RESOURCE("screen.tomewisp.tool.message.invocation.resolve_resource"),
        INVOCATION_SEARCH_RECIPES("screen.tomewisp.tool.message.invocation.search_recipes"),
        INVOCATION_SEARCH_RECIPES_EXACT("screen.tomewisp.tool.message.invocation.search_recipes_exact"),
        INVOCATION_GET_RECIPE("screen.tomewisp.tool.message.invocation.get_recipe"),
        INVOCATION_GET_RECIPE_EXACT("screen.tomewisp.tool.message.invocation.get_recipe_exact"),
        INVOCATION_FIND_USAGES("screen.tomewisp.tool.message.invocation.find_usages"),
        INVOCATION_FIND_USAGES_EXACT("screen.tomewisp.tool.message.invocation.find_usages_exact"),
        INVOCATION_INSPECT_INVENTORY("screen.tomewisp.tool.message.invocation.inspect_inventory"),
        INVOCATION_CRAFTABILITY("screen.tomewisp.tool.message.invocation.craftability"),
        INVOCATION_SEARCH_KNOWLEDGE("screen.tomewisp.tool.message.invocation.search_knowledge"),
        INVOCATION_GET_KNOWLEDGE("screen.tomewisp.tool.message.invocation.get_knowledge"),
        INVOCATION_LIST_KNOWLEDGE_SOURCES("screen.tomewisp.tool.message.invocation.list_knowledge_sources"),
        INVOCATION_GET_MULTIBLOCK("screen.tomewisp.tool.message.invocation.get_multiblock"),
        INVOCATION_LOAD_SKILL("screen.tomewisp.tool.message.invocation.load_skill"),
        INVOCATION_LOAD_SKILL_EXACT("screen.tomewisp.tool.message.invocation.load_skill_exact"),
        INVOCATION_GAME_ENVIRONMENT("screen.tomewisp.tool.message.invocation.game_environment"),
        INVOCATION_INSPECT_GAME_STATE("screen.tomewisp.tool.message.invocation.inspect_game_state"),
        INVOCATION_INSPECT_GAME_STATE_SECTION("screen.tomewisp.tool.message.invocation.inspect_game_state_section"),

        RESULT_PENDING("screen.tomewisp.tool.message.result.pending"),
        RESULT_VALUE_UNAVAILABLE("screen.tomewisp.tool.message.result.value_unavailable"),
        RESULT_COMPLETED("screen.tomewisp.tool.message.result.completed"),
        FAILURE_STALE_REFERENCE("screen.tomewisp.tool.message.failure.stale_reference"),
        FAILURE_UNAVAILABLE("screen.tomewisp.tool.message.failure.unavailable"),
        FAILURE_PLAYER_REQUIRED("screen.tomewisp.tool.message.failure.player_required"),
        FAILURE_INVALID_ARGUMENTS("screen.tomewisp.tool.message.failure.invalid_arguments"),
        FAILURE_FORBIDDEN("screen.tomewisp.tool.message.failure.forbidden"),
        FAILURE_GENERIC("screen.tomewisp.tool.message.failure.generic"),

        RESOLVE_NONE("screen.tomewisp.tool.message.resolve.none"),
        RESOLVE_RETRY("screen.tomewisp.tool.message.resolve.retry"),
        RESOLVE_ONE("screen.tomewisp.tool.message.resolve.one"),
        RESOLVE_MANY("screen.tomewisp.tool.message.resolve.many"),
        RESOLVE_MATCH("screen.tomewisp.tool.message.resolve.match"),
        KNOWLEDGE_NONE("screen.tomewisp.tool.message.knowledge.none"),
        KNOWLEDGE_SCOPE("screen.tomewisp.tool.message.knowledge.scope"),
        KNOWLEDGE_COUNT("screen.tomewisp.tool.message.knowledge.count"),
        KNOWLEDGE_MATCH("screen.tomewisp.tool.message.knowledge.match"),
        DOCUMENT_UNAVAILABLE("screen.tomewisp.tool.message.document.unavailable"),
        DOCUMENT_LOADED("screen.tomewisp.tool.message.document.loaded"),
        DOCUMENT_SOURCE("screen.tomewisp.tool.message.document.source"),
        DOCUMENT_STRUCTURE("screen.tomewisp.tool.message.document.structure"),
        SOURCES_NONE("screen.tomewisp.tool.message.sources.none"),
        SOURCES_COUNT("screen.tomewisp.tool.message.sources.count"),
        SOURCE_ITEM("screen.tomewisp.tool.message.sources.item"),
        SKILL_LOADED("screen.tomewisp.tool.message.skill.loaded"),
        SKILL_TOOLS("screen.tomewisp.tool.message.skill.tools"),
        PLATFORM("screen.tomewisp.tool.message.platform"),
        ENVIRONMENT_DEVELOPMENT("screen.tomewisp.tool.message.environment.development"),
        ENVIRONMENT_NORMAL("screen.tomewisp.tool.message.environment.normal"),
        PLAYER_UNAVAILABLE("screen.tomewisp.tool.message.player.unavailable"),
        PLAYER_IDENTITY("screen.tomewisp.tool.message.player.identity"),
        POSITION_UNAVAILABLE("screen.tomewisp.tool.message.player.position_unavailable"),
        POSITION("screen.tomewisp.tool.message.player.position"),
        PLAYER_INVENTORY_SLOTS("screen.tomewisp.tool.message.player.inventory_slots"),
        MULTIBLOCK_UNAVAILABLE("screen.tomewisp.tool.message.multiblock.unavailable"),
        MULTIBLOCK_VERIFIED("screen.tomewisp.tool.message.multiblock.verified"),
        MULTIBLOCK_BLOCKS("screen.tomewisp.tool.message.multiblock.blocks"),
        MULTIBLOCK_NOT_WORLD_SCAN("screen.tomewisp.tool.message.multiblock.not_world_scan"),
        GAME_STATE_GENERIC("screen.tomewisp.tool.message.game_state.generic"),
        GAME_STATE_SECTION("screen.tomewisp.tool.message.game_state.section"),
        GAME_STATE_OVERVIEW("screen.tomewisp.tool.message.game_state.overview"),
        GAME_STATE_MODS("screen.tomewisp.tool.message.game_state.mods"),
        GAME_STATE_OPTIONS("screen.tomewisp.tool.message.game_state.options"),
        GAME_STATE_PACKS("screen.tomewisp.tool.message.game_state.packs"),
        GAME_STATE_SHADER_PROVIDER("screen.tomewisp.tool.message.game_state.shader_provider"),
        GAME_STATE_SHADER_UNAVAILABLE("screen.tomewisp.tool.message.game_state.shader_unavailable"),
        GAME_STATE_DIAGNOSTICS("screen.tomewisp.tool.message.game_state.diagnostics"),
        GAME_STATE_PLAYER_INVENTORY("screen.tomewisp.tool.message.game_state.player_inventory"),
        GAME_STATE_PLAYER_SCREEN("screen.tomewisp.tool.message.game_state.player_screen"),
        GAME_STATE_WORLD_QUERY("screen.tomewisp.tool.message.game_state.world_query"),
        GAME_STATE_PARTIAL("screen.tomewisp.tool.message.game_state.partial"),
        USAGES_HEADER("screen.tomewisp.tool.message.usages.header"),
        USAGE_INPUT("screen.tomewisp.tool.message.usages.input"),
        USAGE_CATALYST("screen.tomewisp.tool.message.usages.catalyst"),
        USAGE_OUTPUT("screen.tomewisp.tool.message.usages.output"),
        USAGE_BYPRODUCT("screen.tomewisp.tool.message.usages.byproduct"),
        USAGE_RELATED("screen.tomewisp.tool.message.usages.related"),
        CATALOG_PARTIAL("screen.tomewisp.tool.message.catalog.partial"),
        RECIPES_NONE("screen.tomewisp.tool.message.recipes.none"),
        RECIPES_COUNT("screen.tomewisp.tool.message.recipes.count"),
        RECIPE_ITEM("screen.tomewisp.tool.message.recipe.item"),
        RECIPE_ITEM_WORKSTATION("screen.tomewisp.tool.message.recipe.item_workstation"),
        RECIPE_UNAVAILABLE("screen.tomewisp.tool.message.recipe.unavailable"),
        RECIPE_DETAIL("screen.tomewisp.tool.message.recipe.detail"),
        RECIPE_DETAIL_WORKSTATION("screen.tomewisp.tool.message.recipe.detail_workstation"),
        INGREDIENT_GROUPS("screen.tomewisp.tool.message.recipe.ingredient_groups"),
        INGREDIENT("screen.tomewisp.tool.message.recipe.ingredient"),
        RECIPE_OUTPUT("screen.tomewisp.tool.message.recipe.output"),
        RECIPE_BYPRODUCT("screen.tomewisp.tool.message.recipe.byproduct"),
        INVENTORY_KINDS("screen.tomewisp.tool.message.inventory.kinds"),
        INVENTORY_ITEM("screen.tomewisp.tool.message.inventory.item"),
        INVENTORY_PARTIAL("screen.tomewisp.tool.message.inventory.partial"),
        CRAFTABILITY_UNAVAILABLE("screen.tomewisp.tool.message.craftability.unavailable"),
        CRAFTABILITY_READY("screen.tomewisp.tool.message.craftability.ready"),
        CRAFTABILITY_NOT_READY("screen.tomewisp.tool.message.craftability.not_ready"),
        CRAFTABILITY_INCONCLUSIVE("screen.tomewisp.tool.message.craftability.inconclusive"),
        CRAFTABILITY_SUMMARY("screen.tomewisp.tool.message.craftability.summary"),
        CRAFTABILITY_ALLOCATION("screen.tomewisp.tool.message.craftability.allocation"),
        CRAFTABILITY_MISSING("screen.tomewisp.tool.message.craftability.missing");

        private final String translationKey;

        Key(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public GuideToolMessage {
        Objects.requireNonNull(key, "key");
        arguments = List.copyOf(arguments);
        for (String argument : arguments) requireSafeArgument(argument);
    }

    public static GuideToolMessage of(Key key, String... arguments) {
        return new GuideToolMessage(key, List.of(arguments));
    }

    private static void requireSafeArgument(String argument) {
        Objects.requireNonNull(argument, "Tool message argument");
        if (argument.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Tool message arguments must not contain control characters");
        }
    }
}
