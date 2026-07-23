package dev.openallay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.config.ToolFamilyId;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BuiltinToolRegistrationTest {
    @Test
    void modelSurfaceIsResourceVfsPlusCraftability() {
        ToolRegistry registry = new ToolRegistry();
        OpenAllayBootstrap.registerCoreTools(
                registry,
                (invocation, cancellation) -> {
                    throw new AssertionError("catalog test must not execute a Tool");
                },
                new SkillRepository(
                        new SkillParser(),
                        Set.of(
                                "openallay:calculate_craftability",
                                "openallay:resource_list",
                                "openallay:resource_read",
                                "openallay:resource_glob",
                                "openallay:resource_grep",
                                "openallay:resource_query")));
        assertEquals(
                Set.of(
                        "openallay:calculate_craftability",
                        "openallay:load_skill",
                        "openallay:resource_list",
                        "openallay:resource_read",
                        "openallay:resource_glob",
                        "openallay:resource_grep",
                        "openallay:resource_query"),
                registry.descriptors().stream()
                        .map(descriptor -> descriptor.id())
                        .collect(java.util.stream.Collectors.toSet()));

        assertEquals(
                ToolFamilyId.RESOURCE_RESOLUTION,
                ToolFamilyId.forCallableTool("openallay:resource_list").orElseThrow());
        assertEquals(
                ToolFamilyId.RESOURCE_RESOLUTION,
                ToolFamilyId.forCallableTool("openallay:resource_query").orElseThrow());
        assertEquals(
                ToolFamilyId.CRAFTABILITY,
                ToolFamilyId.forCallableTool("openallay:calculate_craftability").orElseThrow());
        assertTrue(ToolFamilyId.forCallableTool("openallay:load_skill").isEmpty());

        for (String retired : Set.of(
                "openallay:find_recipes",
                "openallay:search_recipes",
                "openallay:get_recipe",
                "openallay:find_item_usages",
                "openallay:inspect_inventory",
                "openallay:inspect_game_state",
                "openallay:resolve_resource",
                "openallay:search_knowledge",
                "openallay:get_knowledge_document",
                "openallay:list_knowledge_sources",
                "openallay:get_patchouli_multiblock")) {
            assertTrue(registry.find(retired).isEmpty(), retired);
            assertTrue(ToolFamilyId.forCallableTool(retired).isEmpty(), retired);
        }

        assertEquals(
                Set.of(
                        "openallay:resource_list",
                        "openallay:resource_read",
                        "openallay:resource_glob",
                        "openallay:resource_grep",
                        "openallay:resource_query"),
                Set.copyOf(ToolFamilyId.RESOURCE_RESOLUTION.memberToolIds()));
    }
}
