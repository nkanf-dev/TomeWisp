package dev.openallay.tool;

import java.util.Set;

/** Product-level model catalog policy kept separate from registration and replay compatibility. */
public final class ToolCatalogPolicy {
    private static final Set<String> DISPLAY_ONLY_COMPATIBILITY = Set.of(
            "openallay:resolve_resource",
            "openallay:search_recipes",
            "openallay:get_recipe",
            "openallay:find_item_usages",
            "openallay:inspect_inventory",
            "openallay:inspect_game_state",
            "openallay:search_knowledge",
            "openallay:get_knowledge_document",
            "openallay:list_knowledge_sources",
            "openallay:get_patchouli_multiblock",
            "openallay:find_recipes");

    private ToolCatalogPolicy() {}

    /**
     * Compatibility Tools remain registered for deterministic replay and old history decoding, but
     * a live model cannot discover or invoke them after the Resource VFS migration.
     */
    public static boolean modelAdvertised(String toolId) {
        return !DISPLAY_ONLY_COMPATIBILITY.contains(toolId);
    }

    public static Set<String> displayOnlyCompatibilityTools() {
        return DISPLAY_ONLY_COMPATIBILITY;
    }
}
