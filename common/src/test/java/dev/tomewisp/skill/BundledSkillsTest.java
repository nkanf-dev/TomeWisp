package dev.tomewisp.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class BundledSkillsTest {
    @Test
    void everyBundledSkillIsValidGroundedAndProgressivelyLoadable() {
        Set<String> tools = Set.of(
                "tomewisp:resolve_resource",
                "tomewisp:find_recipes",
                "tomewisp:search_recipes",
                "tomewisp:get_recipe",
                "tomewisp:find_item_usages",
                "tomewisp:inspect_inventory",
                "tomewisp:calculate_craftability",
                "tomewisp:player_context",
                "tomewisp:list_knowledge_sources",
                "tomewisp:search_knowledge",
                "tomewisp:get_knowledge_document",
                "tomewisp:get_patchouli_multiblock");
        SkillRepository repository = new SkillRepository(new SkillParser(), tools);
        assertTrue(repository.reload(new BundledSkillLoader().load(), Set.of("ftbquests")));
        assertEquals(BundledSkillLoader.NAMES.stream().sorted().toList(), repository.metadata().stream()
                .map(SkillMetadata::name).sorted().toList());
        for (SkillMetadata metadata : repository.metadata()) {
            SkillDocument document = repository.find(metadata.name()).orElseThrow();
            assertFalse(document.instructions().isBlank());
            String lower = document.instructions().toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("unavailable") || lower.contains("不可用"));
            assertFalse(repository.metadataPrompt().contains(document.instructions()));
            assertTrue(document.references().isEmpty());
        }
    }
}
