package dev.openallay.tool.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Stable identities for player-facing logical Tool families. */
public enum ToolFamilyId {
    RECIPES(
            "openallay:recipes",
            "recipes.json",
            "openallay:search_recipes",
            "openallay:get_recipe",
            "openallay:find_item_usages",
            "openallay:find_recipes"),
    GUIDES(
            "openallay:guides",
            "guides.json",
            "openallay:list_knowledge_sources",
            "openallay:search_knowledge",
            "openallay:get_knowledge_document",
            "openallay:get_patchouli_multiblock"),
    INVENTORY("openallay:inventory", "inventory.json", "openallay:inspect_inventory"),
    CRAFTABILITY(
            "openallay:craftability",
            "craftability.json",
            "openallay:calculate_craftability"),
    GAME_CONTEXT(
            "openallay:game_context",
            "game_context.json",
            "openallay:inspect_game_state"),
    RESOURCE_RESOLUTION(
            "openallay:resource_resolution",
            "resource_resolution.json",
            "openallay:resolve_resource");

    private final String serializedId;
    private final String fileName;
    private final List<String> memberToolIds;

    ToolFamilyId(String serializedId, String fileName, String... memberToolIds) {
        this.serializedId = serializedId;
        this.fileName = fileName;
        this.memberToolIds = List.of(memberToolIds);
    }

    public String serializedId() {
        return serializedId;
    }

    public String fileName() {
        return fileName;
    }

    public List<String> memberToolIds() {
        return memberToolIds;
    }

    public static ToolFamilyId fromSerializedId(String id) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.serializedId.equals(id))
                .findFirst()
                .orElseThrow(() -> new ToolConfigException(
                        "unknown_tool_family", "Unknown Tool family " + id));
    }

    public static Optional<ToolFamilyId> forCallableTool(String toolId) {
        return Arrays.stream(values())
                .filter(family -> family.memberToolIds.contains(toolId))
                .findFirst();
    }
}
