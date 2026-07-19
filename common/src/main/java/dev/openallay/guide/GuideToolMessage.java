package dev.openallay.guide;

import java.util.List;
import java.util.Objects;

/** Closed locale-independent Tool presentation message safe for history and bridge transport. */
public record GuideToolMessage(Key key, List<String> arguments) {
    public enum Key {
        INVOCATION_RESOLVE_RESOURCE("screen.openallay.tool.message.invocation.resolve_resource"),
        INVOCATION_SEARCH_RECIPES("screen.openallay.tool.message.invocation.search_recipes"),
        INVOCATION_SEARCH_RECIPES_EXACT("screen.openallay.tool.message.invocation.search_recipes_exact"),
        INVOCATION_GET_RECIPE("screen.openallay.tool.message.invocation.get_recipe"),
        INVOCATION_GET_RECIPE_EXACT("screen.openallay.tool.message.invocation.get_recipe_exact"),
        INVOCATION_FIND_USAGES("screen.openallay.tool.message.invocation.find_usages"),
        INVOCATION_FIND_USAGES_EXACT("screen.openallay.tool.message.invocation.find_usages_exact"),
        INVOCATION_INSPECT_INVENTORY("screen.openallay.tool.message.invocation.inspect_inventory"),
        INVOCATION_CRAFTABILITY("screen.openallay.tool.message.invocation.craftability"),
        INVOCATION_SEARCH_KNOWLEDGE("screen.openallay.tool.message.invocation.search_knowledge"),
        INVOCATION_GET_KNOWLEDGE("screen.openallay.tool.message.invocation.get_knowledge"),
        INVOCATION_LIST_KNOWLEDGE_SOURCES("screen.openallay.tool.message.invocation.list_knowledge_sources"),
        INVOCATION_GET_MULTIBLOCK("screen.openallay.tool.message.invocation.get_multiblock"),
        INVOCATION_LOAD_SKILL("screen.openallay.tool.message.invocation.load_skill"),
        INVOCATION_LOAD_SKILL_EXACT("screen.openallay.tool.message.invocation.load_skill_exact"),
        INVOCATION_GAME_ENVIRONMENT("screen.openallay.tool.message.invocation.game_environment"),
        INVOCATION_INSPECT_GAME_STATE("screen.openallay.tool.message.invocation.inspect_game_state"),
        INVOCATION_INSPECT_GAME_STATE_SECTION("screen.openallay.tool.message.invocation.inspect_game_state_section"),

        RESULT_PENDING("screen.openallay.tool.message.result.pending"),
        RESULT_VALUE_UNAVAILABLE("screen.openallay.tool.message.result.value_unavailable"),
        RESULT_COMPLETED("screen.openallay.tool.message.result.completed"),
        FAILURE_STALE_REFERENCE("screen.openallay.tool.message.failure.stale_reference"),
        FAILURE_UNAVAILABLE("screen.openallay.tool.message.failure.unavailable"),
        FAILURE_PLAYER_REQUIRED("screen.openallay.tool.message.failure.player_required"),
        FAILURE_INVALID_ARGUMENTS("screen.openallay.tool.message.failure.invalid_arguments"),
        FAILURE_FORBIDDEN("screen.openallay.tool.message.failure.forbidden"),
        FAILURE_GENERIC("screen.openallay.tool.message.failure.generic"),

        RESOLVE_NONE("screen.openallay.tool.message.resolve.none"),
        RESOLVE_RETRY("screen.openallay.tool.message.resolve.retry"),
        RESOLVE_ONE("screen.openallay.tool.message.resolve.one"),
        RESOLVE_MANY("screen.openallay.tool.message.resolve.many"),
        RESOLVE_MATCH("screen.openallay.tool.message.resolve.match"),
        RESOLVE_SCHEMA("screen.openallay.tool.message.resolve.schema"),
        RESOLVE_ANALYSIS("screen.openallay.tool.message.resolve.analysis"),
        KNOWLEDGE_NONE("screen.openallay.tool.message.knowledge.none"),
        KNOWLEDGE_SCOPE("screen.openallay.tool.message.knowledge.scope"),
        KNOWLEDGE_COUNT("screen.openallay.tool.message.knowledge.count"),
        KNOWLEDGE_MATCH("screen.openallay.tool.message.knowledge.match"),
        DOCUMENT_UNAVAILABLE("screen.openallay.tool.message.document.unavailable"),
        DOCUMENT_LOADED("screen.openallay.tool.message.document.loaded"),
        DOCUMENT_SOURCE("screen.openallay.tool.message.document.source"),
        DOCUMENT_STRUCTURE("screen.openallay.tool.message.document.structure"),
        SOURCES_NONE("screen.openallay.tool.message.sources.none"),
        SOURCES_COUNT("screen.openallay.tool.message.sources.count"),
        SOURCE_ITEM("screen.openallay.tool.message.sources.item"),
        SKILL_LOADED("screen.openallay.tool.message.skill.loaded"),
        SKILL_TOOLS("screen.openallay.tool.message.skill.tools"),
        PLATFORM("screen.openallay.tool.message.platform"),
        ENVIRONMENT_DEVELOPMENT("screen.openallay.tool.message.environment.development"),
        ENVIRONMENT_NORMAL("screen.openallay.tool.message.environment.normal"),
        PLAYER_UNAVAILABLE("screen.openallay.tool.message.player.unavailable"),
        PLAYER_IDENTITY("screen.openallay.tool.message.player.identity"),
        POSITION_UNAVAILABLE("screen.openallay.tool.message.player.position_unavailable"),
        POSITION("screen.openallay.tool.message.player.position"),
        PLAYER_INVENTORY_SLOTS("screen.openallay.tool.message.player.inventory_slots"),
        MULTIBLOCK_UNAVAILABLE("screen.openallay.tool.message.multiblock.unavailable"),
        MULTIBLOCK_VERIFIED("screen.openallay.tool.message.multiblock.verified"),
        MULTIBLOCK_BLOCKS("screen.openallay.tool.message.multiblock.blocks"),
        MULTIBLOCK_NOT_WORLD_SCAN("screen.openallay.tool.message.multiblock.not_world_scan"),
        GAME_STATE_GENERIC("screen.openallay.tool.message.game_state.generic"),
        GAME_STATE_SECTION("screen.openallay.tool.message.game_state.section"),
        GAME_STATE_OVERVIEW("screen.openallay.tool.message.game_state.overview"),
        GAME_STATE_MODS("screen.openallay.tool.message.game_state.mods"),
        GAME_STATE_OPTIONS("screen.openallay.tool.message.game_state.options"),
        GAME_STATE_PACKS("screen.openallay.tool.message.game_state.packs"),
        GAME_STATE_SHADER_PROVIDER("screen.openallay.tool.message.game_state.shader_provider"),
        GAME_STATE_SHADER_UNAVAILABLE("screen.openallay.tool.message.game_state.shader_unavailable"),
        GAME_STATE_DIAGNOSTICS("screen.openallay.tool.message.game_state.diagnostics"),
        GAME_STATE_PLAYER_INVENTORY("screen.openallay.tool.message.game_state.player_inventory"),
        GAME_STATE_PLAYER_SCREEN("screen.openallay.tool.message.game_state.player_screen"),
        GAME_STATE_WORLD_QUERY("screen.openallay.tool.message.game_state.world_query"),
        GAME_STATE_PARTIAL("screen.openallay.tool.message.game_state.partial"),
        USAGES_HEADER("screen.openallay.tool.message.usages.header"),
        USAGE_INPUT("screen.openallay.tool.message.usages.input"),
        USAGE_CATALYST("screen.openallay.tool.message.usages.catalyst"),
        USAGE_OUTPUT("screen.openallay.tool.message.usages.output"),
        USAGE_BYPRODUCT("screen.openallay.tool.message.usages.byproduct"),
        USAGE_RELATED("screen.openallay.tool.message.usages.related"),
        CATALOG_PARTIAL("screen.openallay.tool.message.catalog.partial"),
        RECIPES_NONE("screen.openallay.tool.message.recipes.none"),
        RECIPES_COUNT("screen.openallay.tool.message.recipes.count"),
        RECIPE_ITEM("screen.openallay.tool.message.recipe.item"),
        RECIPE_ITEM_WORKSTATION("screen.openallay.tool.message.recipe.item_workstation"),
        RECIPE_UNAVAILABLE("screen.openallay.tool.message.recipe.unavailable"),
        RECIPE_DETAIL("screen.openallay.tool.message.recipe.detail"),
        RECIPE_DETAIL_WORKSTATION("screen.openallay.tool.message.recipe.detail_workstation"),
        INGREDIENT_GROUPS("screen.openallay.tool.message.recipe.ingredient_groups"),
        INGREDIENT("screen.openallay.tool.message.recipe.ingredient"),
        RECIPE_OUTPUT("screen.openallay.tool.message.recipe.output"),
        RECIPE_BYPRODUCT("screen.openallay.tool.message.recipe.byproduct"),
        INVENTORY_KINDS("screen.openallay.tool.message.inventory.kinds"),
        INVENTORY_ITEM("screen.openallay.tool.message.inventory.item"),
        INVENTORY_PARTIAL("screen.openallay.tool.message.inventory.partial"),
        CRAFTABILITY_UNAVAILABLE("screen.openallay.tool.message.craftability.unavailable"),
        CRAFTABILITY_READY("screen.openallay.tool.message.craftability.ready"),
        CRAFTABILITY_NOT_READY("screen.openallay.tool.message.craftability.not_ready"),
        CRAFTABILITY_INCONCLUSIVE("screen.openallay.tool.message.craftability.inconclusive"),
        CRAFTABILITY_SUMMARY("screen.openallay.tool.message.craftability.summary"),
        CRAFTABILITY_ALLOCATION("screen.openallay.tool.message.craftability.allocation"),
        CRAFTABILITY_MISSING("screen.openallay.tool.message.craftability.missing");

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
